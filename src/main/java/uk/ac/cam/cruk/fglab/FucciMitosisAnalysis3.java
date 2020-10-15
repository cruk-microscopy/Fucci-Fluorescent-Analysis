package uk.ac.cam.cruk.fglab;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.traverse.GraphIterator;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.detection.LogDetectorFactory;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.extra.spotanalyzer.SpotMultiChannelIntensityAnalyzerFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
//import fiji.plugin.trackmate.features.spot.SpotRadiusEstimatorFactory;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.io.TmXmlWriter;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import inra.ijpb.binary.BinaryImages;

import weka.classifiers.Classifier;
import weka.core.Instances;
import weka.core.SerializationHelper;

/**
 * Plugin description goes here
 *
 * @author Ziqiang Huang
 */
public class FucciMitosisAnalysis3 implements PlugIn {
	
	/*
	@Parameter (label="Choose tiff image", persist = true, style="file")
	private static File imgFile = new File ("I:/core/light_microscopy/data/group_folders/Ziqiang/ZH_Sarah/test_substack1.tif");
	
	@Parameter (label = "Tracking channel", persist = true, min = "1")
	private static int targetChannel = 3;
	
	@Parameter (label = "Radius for cell detection (microns)", persist = true, min = "0.01", max = "20")
	private static double spotRadius = 6.5;
	
	@Parameter (label = "Configure extra parameters", persist = false)
	private static boolean extraParam;
	
	protected ImagePlus image;

	// image property members
	private int width;
	private int height;

	// plugin parameters
	public double value;
	public String name;
	*/
	
	final static double minT = 240; // 240 minutes dialog
	protected static Roi[] cellRois;

	@Override
	public void run(String arg) {
		// get user input with parameter dialog
		if (!ParameterDialog.mainDialog())	return;
		
		// timing the start
		IJ.log(GetDateAndTime.getCurrentDate());
		IJ.log(GetDateAndTime.getCurrentTime());
		long start = GetDateAndTime.getCurrentTimeInMs();
		
		// load data as image plus
		ImagePlus imp;
		String imagePath = null;
		if (ParameterDialog.getActiveImage) {
			imp = ParameterDialog.activeImg;
			imagePath = IJ.getDirectory("image");
			if (imagePath==null) {
				IJ.error("active image: " + IJ.getImage().getTitle() + " is not associated with a file!");
				return;
			}
			imp.hide();
		} else {
			imp = IJ.openImage(ParameterDialog.imgPath);
			imagePath = IJ.getDirectory("image");
		} // now image path will be end with Path.separator
		
		// print image information to imagej log window
		GetImageInfo.getInfo(imp);
		
		// get image dimension and swap Z and T if T=1
		int[] dims = imp.getDimensions();	// default order: XYCZT
		if (dims[4] == 1) {
		    imp.setDimensions(dims[2],dims[4],dims[3]);	// set dimension to CTZ
		}
		int numC = imp.getNChannels();
		int numZ = imp.getNSlices();
		int numT = imp.getNFrames();
	
		final double dx = imp.getCalibration().pixelWidth;
		final double dy = imp.getCalibration().pixelHeight;
		final double dt = imp.getCalibration().frameInterval; // in seconds
		
		// prepare ROI Manager
		RoiManager rm = ROIUtility.prepareManager(true);
		rm.runCommand("Open", ParameterDialog.cellRoiPath);
		if (rm.getCount()==0) return;
		// remove small ROIs
		int nROI = rm.getCount();
		ArrayList<Integer> removeList = new ArrayList<Integer>();
		for (int i=0; i<nROI; i++) {
			imp.setRoi(rm.getRoi(i), false);
			//if (i%100==0) System.out.println("debug, rm i " + i + ", area: " + imp.getStatistics().area);
			if (imp.getStatistics().area <= 15)
				removeList.add(i);
		}
		int size = removeList.size();
		if (size>0) {
			int[] index = new int[size];
			for (int i=0; i<size; i++) {
				index[i] = removeList.get(i);
			}
			rm.setSelectedIndexes(index);
			rm.runCommand("Delete");
		}
		nROI = rm.getCount();
		//System.out.println("debug nROI:" + nROI);
		cellRois = rm.getRoisAsArray();
		//cellRois = new Roi[nROI];
		
		for (int i=0; i<nROI; i++) {
			int t = 0;
			if (!rm.getRoi(i).hasHyperStackPosition())
				t = rm.getRoi(i).getPosition();
			else
				t = rm.getRoi(i).getTPosition();
			cellRois[i].setPosition(0, 0, t);
		}
		rm.reset();
		//cellRois = rm.getRoisAsArray();
		// setup trackmate based on spot ROIs
		TrackMate trackmate = create_trackmate( cellRois, imp );
		if (!process(trackmate)) return;
		
		System.out.println("trackmate: spot count: " + String.valueOf(trackmate.getModel().getSpots().getNSpots(true)));
	 	Model model = trackmate.getModel();
	 	IJ.log("      TrackMate completed successfully.");
	 	IJ.log(String.format("      Found %d spots in %d tracks.", model.getSpots().getNSpots(true), model.getTrackModel().nTracks(true)));
			
		/*
		 *  Use TrackMate result to further analyze Fucci fluorescence and mitosis
		 */
			
			// prepare temporary variables for mitosis detection and labellings
			int nZeros = String.valueOf(model.getSpots().getNSpots(true)).length();	// padding left with zero for spot index
			//ArrayList<Integer> mitosisTrackIDs = new ArrayList<Integer>();
			//ArrayList<Spot> mitosisSpots = new ArrayList<Spot>();
			//ArrayList<Integer> mitosisRoiIDs = new ArrayList<Integer>();
			//Map<String, ArrayList<Integer>> mitosisCells = new HashMap<String, ArrayList<Integer>>();
			
			boolean isMitosis = false;
			//boolean cellIsMitosis = false;
			int nMitosis = 0;
			ImagePlus impCrop = new ImagePlus();
			/*
			 *  Construct ResultTable and RoiManager
			 */
				final String XML_NAME = "Track Model";
				final String TABLE_NAME = "Tracking Data Table";
				final String CELL_ROI_NAME = "Tracking Cell ROI";
				final String SINGLE_TRACK_NAME = "Single Cell Tracks";
				final String MITOSIS_TRACK_NAME = "Mitosis Tracks";
				final ResultsTable table = new ResultsTable();
				// prepare RoiManager for operation
				rm.reset();
				int roiCount = 0;
				
			// prepare arraylist for mitosis track
			//ArrayList<Spot> mitosisSpotList = new ArrayList<Spot>();
			//ArrayList<Integer> mitosisTrackIdx = new ArrayList<Integer>();

			TrackModel tm = model.getTrackModel();
			Set<Integer> trackIDs = tm.trackIDs(true);
			TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();
			double pixelSize = imp.getCalibration().pixelWidth;
			
			// prepare temporary variables for single track and lineage extraction
			Map<String, ArrayList<Integer>> singleCells = new HashMap<String, ArrayList<Integer>>();
			ArrayList<Spot> startSpots = new ArrayList<Spot>(); // store starting spot for each track
			for (final Integer trackID : trackIDs) {
				Set<Spot> spots = tm.trackSpots(trackID);
				for (Spot spot : spots) {
					int nSource = neighborIndex.predecessorsOf(spot).size();
					if (nSource==0) {
						startSpots.add(spot);
					}
				}
			}
			//System.out.println("debug: numC: " + numC);
			// we iterate through all starting spot (spot has no predecessor)
			for (Spot startSpot : startSpots) {
				int trackID = tm.trackIDOf(startSpot);
				// 3 variables for temporary store track segments to form the single tracks
				ArrayList<ArrayList<Integer>> segments = new ArrayList<ArrayList<Integer>>();
				ArrayList<Integer> seg = new ArrayList<Integer>();
				String lineageID = String.valueOf(trackID) + "-";
				
				GraphIterator<Spot, DefaultWeightedEdge> iter = tm.getDepthFirstIterator(startSpot, true);
				
				while (iter.hasNext()) {
					Spot currentSpot = iter.next();
					
					//String roiName = "ROI:" + String.format("%0"+String.valueOf(nZeros)+"d", ++roiCount) + ", ID:" + lineageID;
					String roiName = "ROI:" + String.format("%0"+String.valueOf(nZeros)+"d", currentSpot.ID()) + ", ID:" + lineageID;
					
					double x = currentSpot.getFeature("POSITION_X");
					double y = currentSpot.getFeature("POSITION_Y");
					double t = currentSpot.getFeature("FRAME") + 1;
					//double estimatedDiameter = currentSpot.getFeature("ESTIMATED_DIAMETER");
					double d = ParameterDialog.spotRadius*2;
					// generate circle selection and add to RoiManager
					int idx = getRoiIndex (x/pixelSize, y/pixelSize, t);	
					Roi spotRoi = (idx==-1) ? new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize) : cellRois[idx];
					spotRoi.setPosition(0, 1, (int)t);
				
					
					
					rm.add(imp, spotRoi, currentSpot.ID());	// add roi with imp to RoiManager
					rm.rename(rm.getCount()-1, roiName);	
					seg.add(rm.getCount()-1);
					
					// try, get link cost between source and current (-1 if no source)
					double linkCost = -1;
					
					// check segment condition
					int endID = -1;
					List<Spot> predecessor = neighborIndex.predecessorListOf(currentSpot);
					if (predecessor.size() == 1) {
						endID = predecessor.get(0).ID();
						linkCost = tm.getEdgeWeight(tm.getEdge(predecessor.get(0), currentSpot));
					}
					
					
					

					int nTarget = neighborIndex.successorsOf(currentSpot).size();
					switch(nTarget) {
						case 2: // cell has two daughter cells
							lineageID += "0";
							segments.add(seg);
							seg = new ArrayList<Integer>();
							break;
						case 0: // current cell ends
							// get stored segments into a single track, and then add to cell list
							ArrayList<Integer> singleTracks = new ArrayList<Integer>();
							for (ArrayList<Integer> segment : segments) {
								singleTracks.addAll(segment);
							}
							singleTracks.addAll(seg);
							singleCells.put(lineageID, singleTracks);
							seg = new ArrayList<Integer>();
							// clear current seg buffer, remove last part of the segments
							if (lineageID.endsWith("0")) {
								lineageID = lineageID.substring(0, lineageID.length()-1) + "1";
							} else if (lineageID.endsWith("1")) {
								//lineageID = lineageID.substring(0, lineageID.length()-1);
								while(lineageID.endsWith("1")) {
									lineageID = lineageID.substring(0, lineageID.length()-1);
									segments.remove(segments.size()-1);
								}
								lineageID = lineageID.substring(0, lineageID.length()-1);
								lineageID += "1";
							} else if (lineageID.endsWith("-")) { // do nothing for single cell track	
							} else System.out.println("Error: " + roiName);
							break;
						case 1:	// do nothing
							break;
						default: // something is wrong
							System.out.println("Error: " + roiName);
					}
					
					imp.deleteRoi();
					
					table.incrementCounter();
					table.addValue( "Spot ID", currentSpot.ID() );
					table.addValue( "Source Spot ID", endID );
					table.addValue("link Cost", linkCost);
					table.addValue( "Target Spot number", nTarget );
					//table.addValue( "Track ID", trackID );
					table.addValue( "Lineage ID", lineageID);
					//table.addValue( "estimated diameter", estimatedDiameter); 
					table.addValue( "X", x );
					table.addValue( "Y", y );
					table.addValue( "T", t );
					
					Roi tempRoi = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
					tempRoi.setPosition(0, 1, (int)t);
					// use the circle ROI to crop around original image and do mitosis check
					
					imp.setRoi(tempRoi, false);
					for (int i=0; i<numC; i++) {
						imp.setPositionWithoutUpdate(i+1, tempRoi.getZPosition(), tempRoi.getTPosition());
						table.addValue("C"+String.valueOf(i+1), imp.getRawStatistics().mean);
					}
					
					/*
					for (int i=0; i<numC; i++) {	// iterate through all channels to get mean intensity (diameter is predefined, not estimated)
						String value = String.format("MEAN_INTENSITY%02d", (i+1));
						double cValue = Double.NaN;
						
						try {
							cValue = currentSpot.getFeature(value);
						} catch (NullPointerException e) {
							//System.out.println("Cannot get channel intensity for spot: " + currentSpot.ID());
						}
						table.addValue( "C"+String.valueOf(i+1), cValue);
					}
					*/
				}
			}
			// clear cache
			imp.deleteRoi(); imp.setOverlay(null);
			impCrop.changes=false; impCrop.close();
			
			System.gc();
			// prepare result table
			table.setPrecision( 3 );
			table.show( TABLE_NAME );
			IJ.log("      Found in total " + String.valueOf(nMitosis) + " mitosis event.");

			
			
		/*
		 *  Saving result to disk
		 */
			String savePath = null;
			if (!ParameterDialog.saveWithImg) {
				savePath = ParameterDialog.saveDir;
				if (!savePath.endsWith(File.separator)) savePath += File.separator;
			} 
			if (savePath == null) {
				savePath = imagePath;
			}
			// save trackmate result as xml file
			//model.getLogger().log(model.toString());
			if (ParameterDialog.saveModel) {
				String tmXMLPath = savePath + XML_NAME +  " (" + imp.getTitle() + ").xml";
				File xmlFile = new File(tmXMLPath);
				Logger logger = trackmate.getModel().getLogger();
				TmXmlWriter writer = new TmXmlWriter(xmlFile, logger);
				writer.appendLog(logger.toString());
				writer.appendModel(trackmate.getModel());
				writer.appendSettings(trackmate.getSettings());
				try {
					writer.writeToFile();
				} catch (IOException e) {
					IJ.error("Can not save track model! Current tracking result will not be modifiable later!");
					e.printStackTrace();
				}
			}
			// save data table
			if (ParameterDialog.saveTable) {
				table.save(savePath + TABLE_NAME + " (" + imp.getTitle() + ").csv");
			}
			// save all track spots in Roimanager to roiset.zip
			Roi[] trackSpotRois = rm.getRoisAsArray();
			if (ParameterDialog.saveROI) {			
				rm.runCommand("Save", savePath + CELL_ROI_NAME + " (" + imp.getTitle() + ").zip");
			}
			
			//RoiManagerUtility.restoreManager();
			rm.setVisible(true);
			
			imp.show();
			if (ParameterDialog.viewInGUI) display_results_in_GUI(trackmate, imp);
			long end = GetDateAndTime.getCurrentTimeInMs();
			String duration = GetDateAndTime.getDuration(end-start);
			IJ.log("Fucci mitosis analysis completed successfully after : " + duration);
	}


	private SpotCollection spots_from_RoiManager(Roi[] cellRois, ImagePlus imp) {
		if (imp==null || cellRois==null || cellRois.length==0) return null;
		int nSpots = cellRois.length;
		double pixelSize = imp.getCalibration().pixelWidth;
		double pixelArea = pixelSize * pixelSize;
		double frameInterval = imp.getCalibration().frameInterval;
		SpotCollection spots = new SpotCollection();
	 
	    for (int i=0; i<nSpots; i++) {
	    	Roi roi = cellRois[i];
	    	imp.setRoi(roi, false);
	    	double[] center = roi.getContourCentroid();
	    	//double x = imp.getAllStatistics().xCentroid;
	        //double y = imp.getAllStatistics().yCentroid;
	    	double x = center[0] * pixelSize;
	    	double y = center[1] * pixelSize;
	    	int frame = roi.getTPosition();
	    	double area = roi.getStatistics().area * pixelArea;
	    	double t = (frame - 1) * frameInterval;
			double radius = Math.sqrt( area / Math.PI);
			double quality = (double)i;
	        Spot spot = new Spot(x, y, 0, radius, quality);
	        spot.putFeature( "POSITION_T", t);
	        spots.add( spot, (int)(frame-1));
	    }
	    return spots;
	}
	
	private boolean process (TrackMate trackmate) {
		//trackmate = create_trackmate(newSpotRois.toArray(), imp);
 		boolean ok = trackmate.checkInput();
 	    // Initial filtering
 		ok = ok && trackmate.execInitialSpotFiltering();
 	    // Compute spot features.
 	    ok = ok && trackmate.computeSpotFeatures( true );
 	    // Filter spots.
 	    ok = ok && trackmate.execSpotFiltering( true );
 	    // Track spots.
 	    ok = ok && trackmate.execTracking();
 	    // Compute track features.
 	    ok = ok && trackmate.computeTrackFeatures( true );
 	    // Filter tracks.
 	    ok = ok && trackmate.execTrackFiltering( true );
 	    // Compute edge features.
 	    ok = ok && trackmate.computeEdgeFeatures( true );
 	    //ok = ok && trackmate.process();
	 	
		return ok;
	}
	private TrackMate create_trackmate(Roi[] cellRois, ImagePlus imp) {
	    /*
	    Creates a TrackMate instance configured to operated on the specified
	    ImagePlus imp with cell analysis stored in the specified ResultsTable
	    results_table.
	    */
	     
	    Calibration cal = imp.getCalibration();
	     
	    // TrackMate.
	     
	    // Model.
	    Model model = new Model();
	    model.setLogger( Logger.DEFAULT_LOGGER );
	    model.setPhysicalUnits( cal.getUnit(), cal.getTimeUnit() );
	     
	    // Settings.
	    Settings settings = new Settings();
	    settings.setFrom( imp );
	     
	    // Create the TrackMate instance.
	    //settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
	    TrackMate trackmate = new TrackMate( model, settings );
	     
	    // Add ALL the feature analyzers known to TrackMate, via
	    // providers. 
	    // They offer automatic analyzer detection, so all the 
	    // available feature analyzers will be added. 
	    // Some won't make sense on the binary image (e.g. contrast)
	    // but nevermind.
	     
	    SpotAnalyzerProvider spotAnalyzerProvider = new SpotAnalyzerProvider();
		for (String key : spotAnalyzerProvider.getKeys()) {
			settings.addSpotAnalyzerFactory( spotAnalyzerProvider.getFactory( key ) );
		}
		
		EdgeAnalyzerProvider edgeAnalyzerProvider = new EdgeAnalyzerProvider();
		for (String key : edgeAnalyzerProvider.getKeys()) {
			settings.addEdgeAnalyzer( edgeAnalyzerProvider.getFactory( key ) );
		}
		TrackAnalyzerProvider trackAnalyzerProvider = new TrackAnalyzerProvider();
		for (String key : trackAnalyzerProvider.getKeys()) {
			settings.addTrackAnalyzer( trackAnalyzerProvider.getFactory( key ) );
		}
	     
	    trackmate.getModel().getLogger().log( settings.toStringFeatureAnalyzersInfo() );
	    trackmate.computeSpotFeatures( true );
	    trackmate.computeEdgeFeatures( true );
	    trackmate.computeTrackFeatures( true );
	     
	    // Skip detection && get spots from results table.
	    //Roi[] spotRois = rm.getSelectedRoisAsArray();
	    SpotCollection spots = spots_from_RoiManager(cellRois, imp);
	    model.setSpots( spots, false );
	     
	    // Configure detector. We put nothing here, since we already have the spots 
	    // from previous step.
	    settings.detectorFactory = new ManualDetectorFactory();
	    settings.detectorSettings = new HashMap<String, Object>();
	    settings.detectorSettings.put( "RADIUS" , 3.0d);
	    // Configure tracker
	    settings.trackerFactory = new SparseLAPTrackerFactory();
	    settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
	    settings.trackerSettings.put( "LINKING_MAX_DISTANCE" , 55.0d);
	    settings.trackerSettings.put( "GAP_CLOSING_MAX_DISTANCE" , 55.0d);
	    settings.trackerSettings.put( "MAX_FRAME_GAP" , 4);
	    settings.trackerSettings.put( "ALLOW_TRACK_SPLITTING" ,  true);
		settings.trackerSettings.put( "ALLOW_TRACK_MERGING" ,  false);
	    settings.initialSpotFilterValue = -1.0;

	    return trackmate;
	}
	
	private int getRoiIndex (double x, double y, double t) {// x, y in pixel unit
		if (cellRois==null || cellRois.length==0) return -1;
		for (int i=0; i<cellRois.length; i++) {
			if (cellRois[i].getTPosition()!=t) continue;
			if (cellRois[i].contains((int)x, (int)y)) return i;
		}
		return -1;
	}
	
	private void display_results_in_GUI( TrackMate trackmate, ImagePlus imp ) {
	     
	    TrackMateGUIController gui = new TrackMateGUIController( trackmate );
	 
	    // Link displayer and GUI.
	     
	    Model model = trackmate.getModel();
	    SelectionModel selectionModel = new SelectionModel( model);
	    HyperStackDisplayer displayer = new HyperStackDisplayer( model, selectionModel, imp );
	    gui.getGuimodel().addView( displayer );
	    Map<String, Object> displaySettings = new HashMap<>();
	    displaySettings = gui.getGuimodel().getDisplaySettings();
	    
	    for (String key : displaySettings.keySet()) {
	        displayer.setDisplaySettings( key, displaySettings.get( key ) );
	    }
	    displayer.render();
	    displayer.refresh();
	     
	    gui.setGUIStateString( "ConfigureViews" );
	}
	
	public static void main(String[] args) {

		/*
		if (IJ.versionLessThan("1.52f")) System.exit(0);

		String [] ij_args = 
			{ "-Dplugins.dir=C:/Fiji.app/plugins",
			"-Dmacros.dir=C:/Fiji.app/macros" };

		ij.ImageJ.main(ij_args);
		*/
		//fiji fj = new fiji();
		
		
		
		FucciMitosisAnalysis3 fma = new FucciMitosisAnalysis3();
		fma.run(null);
		
		
	}
}
