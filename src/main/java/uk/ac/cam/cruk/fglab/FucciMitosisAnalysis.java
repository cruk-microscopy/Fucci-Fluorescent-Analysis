package uk.ac.cam.cruk.fglab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.detection.LogDetectorFactory;
import fiji.plugin.trackmate.extra.spotanalyzer.SpotMultiChannelIntensityAnalyzerFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotRadiusEstimatorFactory;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;

/**
 * Plugin description goes here
 *
 * @author Ziqiang Huang
 */
public class FucciMitosisAnalysis implements PlugIn {
	
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

	@Override
	public void run(String arg) {
		
		// check if 3rd party plugin have been installed
		boolean installTrackMateExtras = false;
		boolean installFeatureJ = false;
		boolean installImageScience = false;
		if (!PluginUtility.pluginCheck("TrackMate_extras", null)) {
			installTrackMateExtras = PluginUtility.installTrackMateExtras();
			if (!installTrackMateExtras) {
				IJ.log("   \"TrackMate_extras\" not installed!");
				IJ.log("   Consider installing it manually.");
				return;
			}
		}
		// ignore FeatureJ installation
		if (!PluginUtility.pluginCheck("imagescience", PluginUtility.jarDir())) {
			installImageScience = PluginUtility.installImageScience();
			if (!installImageScience) {
				IJ.log("   \"Imagescience\" not installed!");
				IJ.log("   Consider installing it manually.");
				return;
			}
		}

		if (installTrackMateExtras || installImageScience) {
			IJ.log("   3rd party plugin installed.");
			IJ.log("   Restart Fiji to run the Fucci toolbox plugin.");
			return;
		}
		
		// get user input with parameter dialog
		ParameterDialog pd = new ParameterDialog();
		if (!pd.mainDialog())	return;
		if (pd.doWeka) {
			if (!MitosisUtility.makeModelDir())
				return;
		}
		
		// timing the start
		IJ.log(GetDateAndTime.getCurrentDate());
		IJ.log(GetDateAndTime.getCurrentTime());
		long start = GetDateAndTime.getCurrentTimeInMs();
		
		// open file as image plus
		ImagePlus imp;
		String imagePath = null;
		if (pd.getActiveImage) {
			imp = pd.activeImg;
			imagePath = IJ.getDirectory("image");
			imp.hide();
		} else {
			imp = IJ.openImage(pd.imgPath);
			imagePath = IJ.getDirectory("image");
		}
		
		// print image information to imagej log window
		GetImageInfo.getInfo(imp);
		
		// get image dimension and swap Z and T if T=1
		int[] dims = imp.getDimensions();	// default order: XYCZT
		if (dims[4] == 1) {
		    imp.setDimensions(dims[2],dims[4],dims[3]);	// set dimension to CTZ
		}
		int nChannels = imp.getNChannels();
		
		// Setup settings for TrackMate
		Settings settings = new Settings();
		settings.setFrom(imp);
		// Spot analyzer: we want the multi-C intensity analyzer.
		settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
		settings.addSpotAnalyzerFactory(new SpotRadiusEstimatorFactory());
		// Spot detector
		settings.detectorFactory = new LogDetectorFactory();
		//settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
		//IJ.log(settings.detectorSettings);	//debug
		Map<String, Object> map = settings.detectorFactory.getDefaultSettings();
		map.put("TARGET_CHANNEL", pd.targetChannel);
		map.put("RADIUS", pd.spotRadius);
		map.put("THRESHOLD", pd.qualityThreshold);	//dialog
		map.put("DO_MEDIAN_FILTERING", false);
		map.put("DO_SUBPIXEL_LOCALIZATION", true);
		settings.detectorSettings = map;
		//IJ.log(settings.detectorSettings);	//debug
		
		// Spot tracker
		settings.trackerFactory = new SparseLAPTrackerFactory();
		//settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		map = LAPUtils.getDefaultLAPSettingsMap();
		map.put("ALLOW_TRACK_SPLITTING", true);
		map.put("ALLOW_TRACK_MERGING", false);
		map.put("MAX_FRAME_GAP", pd.frameGap);	//dialog
		map.put("LINKING_MAX_DISTANCE", pd.linkingMax);
		map.put("GAP_CLOSING_MAX_DISTANCE", pd.closingMax);
		settings.trackerSettings = map;
		

		// add link/edge analyzer
		settings.addEdgeAnalyzer(new EdgeTargetAnalyzer());

		// Run TrackMate and store data into Model
		Model model = new Model();
		TrackMate trackmate = new TrackMate(model, settings);

		if (!(trackmate.checkInput()) || !(trackmate.process())) {
		    IJ.log("Could not execute TrackMate: " + trackmate.getErrorMessage());
		    return;
		}

		IJ.log("      TrackMate completed successfully.");
		IJ.log(String.format("      Found %d spots in %d tracks.", model.getSpots().getNSpots(true), model.getTrackModel().nTracks(true)));
		
		// use TrackMate result to further analyze Fucci fluorescence and mitosis
		int nTracks = model.getTrackModel().nTracks(true);
		int nZeros = String.valueOf(nTracks).length();
		boolean isMitosis;
		int nMitosis = 0;
		double[]  mitosisTrackLength = new double[3];
		double frameInterval = imp.getCalibration().frameInterval;

		// Construct Result table and RoiManager
		final String TABLE_NAME = "Fucci cell tracking data table";
		final ResultsTable table = new ResultsTable();
		
		// prepare RoiManager for operation
		RoiManagerUtility.resetManager();
		RoiManager rm = RoiManager.getInstance2();
		RoiManagerUtility.hideManager();
		
		//RoiManager rm = RoiManager.getInstance();
		//if (rm==null) rm = new RoiManager();
		//rm.reset();
		//rm.setVisible(false);
		
		// prepare arraylist for mitosis track
		ArrayList<Spot> mitosisSpotList = new ArrayList<Spot>();
		ArrayList<Integer> mitosisTrackIdx = new ArrayList<Integer>();

		TrackModel tm = model.getTrackModel();
		Set<Integer> trackIDs = tm.trackIDs(true);
		TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();

		double d;
		double x, y, t;
		double pixelSize = imp.getCalibration().pixelWidth;
		double[] thresholds = MitosisUtility.getMitosisDetectionThreshold(imp, 1, 3, pd.mitoSensitivity);
		
		// storage for mitosis track and spots
		Set<Integer> mitosisTrackIDs = new HashSet<Integer>();
		Set<List<Spot>> mitosisSpots = new HashSet<List<Spot>>();
		ArrayList<Spot> mitosisSpots2 = new ArrayList<Spot>();
		ArrayList<OvalRoi> mitosisRois = new ArrayList<OvalRoi>();
		//int spotIdx = 1;
		
		//final double mitosisProbThres = 0.3;	// dialog
		for (final Integer trackID : trackIDs) {	// iterate through tracks (alphabetically ordered)
			//dTrackMax = 0;	// get largest spot diameter for the current track
			Set<Spot> spots = tm.trackSpots(trackID);

			// sort spots by ascending frame order
			ArrayList<Spot> sortedSpots = new ArrayList<Spot>();
			sortedSpots.addAll(spots);
			sortedSpots.sort(Spot.frameComparator);
			
			
			// get starting spot of the curren track;
			Spot trackStartSpot = sortedSpots.get(0);
			boolean mitosisTrack = false;
			
			for (Spot spot : spots) {// iterate through spots in track
				
				// get current Spot's successors (target Spots as set)
				Set <Spot> successors = neighborIndex.successorsOf(spot);
				int nTarget = successors.size();
				isMitosis = false;
				if (nTarget == 2) {
					if (pd.doWeka) {
						String mitosisDetectorPath = pd.modelPath;
						if (mitosisDetectorPath == null) {
							mitosisDetectorPath = MitosisUtility.modelDir;
						}
						isMitosis = MitosisUtility.mitosisCheckWithTrainedModel(spot, imp, pd.spotRadius*3, mitosisDetectorPath);
					} else {
						double rx = spot.getFeature("POSITION_X");
						double ry = spot.getFeature("POSITION_Y");
						double rt = spot.getFeature("FRAME") + 1;
						//d = spot.getFeature('ESTIMATED_DIAMETER');
						double rd = pd.spotRadius*2;
						// if the spot is in the object ROI count
						// display spot as roi
						// generate circle selection and add to RoiManager
						
						Roi rr = new OvalRoi((rx-rd/2)/pixelSize,(ry-rd/2)/pixelSize, rd/pixelSize, rd/pixelSize);
						rr.setPosition(pd.targetChannel, 1, (int)rt);
						isMitosis = MitosisUtility.mitosisCheckQuick(imp, rr, 1, 3, thresholds);
						//isMitosis = MitosisUtility.mitosisCheck(tm, spot, pd.mitoProbThres);
					}
				}
				
				if (isMitosis) {
					++nMitosis;
					if (!mitosisTrackIDs.contains(trackID)) {
						mitosisTrackIDs.add(trackID);
						mitosisSpots2.add(spot);
						mitosisTrack = true;
					}	
				}
				
				if (nTarget==0 && mitosisTrack) {
					List<Spot> singleTrackSpots = new ArrayList<Spot>();
					singleTrackSpots.add(trackStartSpot); // as start spot of a single track contains mitosis event
					singleTrackSpots.add(spot);	// as end spot of a single track contains mitosis event
					mitosisSpots.add(singleTrackSpots);
				}
				
				// get current Spot's source Spot
				int endID = -1;
				List<Spot> predecessor = neighborIndex.predecessorListOf(spot);
				if (predecessor.size() == 1) {
					endID = predecessor.get(0).ID();
				}
				
				d = spot.getFeature("ESTIMATED_DIAMETER");
				
				t = spot.getFeature("FRAME") + 1;
				x = spot.getFeature("POSITION_X"); y = spot.getFeature("POSITION_Y");

				// generate circle selection and add to RoiManager
				OvalRoi r = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
				r.setPosition(pd.targetChannel,1,(int)t);
				
				
				rm.add(imp, r, spot.ID());	// add roi with imp to RoiManager, SpotID as 3rd argument
				//String spotRoiName = String.format("%0"+String.valueOf(nZeros)+"d", spotIdx);
				//rm.rename(rm.getCount()-1, spotRoiName);
				//spotIdx++;
				
				if (isMitosis) {
					mitosisRois.add(new OvalRoi((x-pd.spotRadius)/pixelSize,(y-pd.spotRadius)/pixelSize, pd.spotRadius*2/pixelSize, pd.spotRadius*2/pixelSize));
				}
				IJ.run(imp, "Select None", "");
				
				table.incrementCounter();
				table.addValue( "Spot ID", spot.ID() );
				table.addValue( "Source Spot ID", endID );
				table.addValue( "Target Spot number", nTarget );
				table.addValue( "mitosis", isMitosis?"yes":"no" );
				table.addValue( "Track ID", trackID );
				table.addValue( "estimated diameter", d); 
				table.addValue( "X", x );
				table.addValue( "Y", y );
				table.addValue( "T", t );

				for (int i=0; i<nChannels; i++) {	// iterate through all channels to get mean intensity (diameter is predefined, not estimated)
					String value = String.format("MEAN_INTENSITY%02d", (i+1));
					table.addValue( "C"+String.valueOf(i+1), spot.getFeature(value));
				}
			
			}
		}

		table.setPrecision( 3 );
		table.show( TABLE_NAME );
		//rm.setVisible(true);
		IJ.log("      Found in total " + String.valueOf(nMitosis) + " mitosis event.");

		
		
		// result saving part
		String savePath = null;
		if (!pd.saveWithImg) {
			savePath = pd.saveDir;
		} 
		if (savePath == null) {
			savePath = imagePath;
		}
		
		if (pd.saveTable) {
			try {
				table.saveAs(savePath + File.separator + TABLE_NAME + ".csv");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		Roi[] trackSpotRois = RoiManagerUtility.managerToRoiArray();
		if (pd.saveROI) {
			rm.runCommand("Save", savePath + File.separator  + "track spots.zip");
			//rm.close();
		}
		RoiManagerUtility.resetManager();
		int roiID = 0;
		for (OvalRoi r : mitosisRois) {
			rm.add(imp, r, ++roiID);
		}
		rm.runCommand("Save", savePath + File.separator  + "mitosis spots.zip");
		RoiManagerUtility.resetManager();
		//RoiManagerUtility.roiArrayToManager(trackSpotRois, true, false);
		
		
		// ToDo:	add mitosis spreadsheet data with trackstack;
		// 			add mitosis summary spreadsheet
		//			identify 1st, 2nd, 3rd mitosis
		//			filter bad tracks based on channel intensity change
		int idx = 0;
		if (pd.saveMitosisTrackStack) {
			
			String mitoSavePath = savePath + File.separator + "mitosis tracks";
			File mitoSaveDir = new File(mitoSavePath);
			if (!mitoSaveDir.exists())	mitoSaveDir.mkdirs();
			
			imp.hide();
			for (List<Spot> mitosisSpot : mitosisSpots) {
				
				Spot startSpot = mitosisSpot.get(0);
				Spot endSpot = mitosisSpot.get(1);
				
				
				List<DefaultWeightedEdge> edges = tm.dijkstraShortestPath(startSpot, endSpot);

				
				double trackDuration = (edges.size() * imp.getCalibration().frameInterval)/60;
				
				if (trackDuration < pd.minLength) {
					continue;
				} else {
					++idx;
				}

				ImagePlus impStack = trackStackViewer.saveTrackAsStack(imp, tm, edges);
				String stackFileName = "Mitosis_" + String.format("%0"+String.valueOf(nZeros)+"d", idx);
				if (startSpot==null || endSpot==null) {
					IJ.log("ID is null!");
					stackFileName += ".tif"; 
				} else {
					stackFileName = "Track_Spot_" + startSpot.ID() + "-" + endSpot.ID() + ".tif";
				}
				impStack.setTitle("mitosis track "+String.valueOf(idx));
				IJ.saveAs(impStack, "Tiff", mitoSavePath + File.separator + stackFileName);
				impStack.close();
				
				String stackRoiPath = mitoSavePath + File.separator + stackFileName;
				stackRoiPath = stackRoiPath.substring(0, stackRoiPath.length()-4) + ".zip";
				trackStackViewer.saveTrackAsRoi(imp, tm, edges, pd.spotRadius*2, pd.targetChannel, mitosisSpots2, thresholds, pd.modelPath, stackRoiPath);
				
			}
			imp.deleteRoi();
		}
		
		RoiManagerUtility.resetManager();
		RoiManagerUtility.roiArrayToManager(trackSpotRois, true, false);
		RoiManagerUtility.showManager();
		imp.show();
		
		long end = GetDateAndTime.getCurrentTimeInMs();
		String duration = GetDateAndTime.getDuration(end-start);
		IJ.log("Fucci mitosis analysis completed successfully after : " + duration);

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
		
		
		
		FucciMitosisAnalysis fma = new FucciMitosisAnalysis();
		fma.run(null);
		
		
	}
}
