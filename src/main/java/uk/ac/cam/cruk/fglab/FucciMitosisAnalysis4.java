package uk.ac.cam.cruk.fglab;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

import fiji.plugin.trackmate.FeatureModel;
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
import ij.process.ImageStatistics;
import inra.ijpb.binary.BinaryImages;

import weka.classifiers.Classifier;
import weka.core.Instances;
import weka.core.SerializationHelper;

/**
 * Plugin description goes here
 *
 * @author Ziqiang Huang
 */
public class FucciMitosisAnalysis4 implements PlugIn {
	
	/*
	 * Generate cell ROI:
	 * 			input:	channel, (cell diameter, quality threshold)
	 * 			output: cell ROI
	 * 
	 * Optional: classify 
	 * Construct TrackMate:
	 * 			input:	spot (from StarDist ROI / TrackMate), (linkingMax, frameGap)
	 * 			output:	1st trackmate model
	 * 
	 * Modify Tracks:
	 * 			input: track spots, (minimum cost for wrong link, minimum similarity score, search range)
	 * 			compute: for high cost links, compute similar cells as its new target/source
	 * 
	 * Configure ROI and lineage:
	 * 
	 * 
	 * Export result
	 * 
	 * 
	 */
	
	private double minTrackLength = 240d; // 240 minutes dialog
	
	private int targetChannel = 3;	// target channel for spot detection / cell ROI generation
	private double spotRadius = 6.5d;	// spot radius for LoG spot detection
	private double qualityThreshold = 10.0d;	// quality threshold for LoG spot detection
	private double linkingMax = 55.0d;	// maximum spot linking / gap closing distance
	private double frameGap = 3.0d;	// maximum frame gap
	
	private double minCost = 150.0d;	// minimum linking cost to be wrong link
	private double minSimilarity = 9.0d;	// minimum similarity score (between 2 cells)
	private double[] searchRange = {20, 20, 1};	// search range for similar cells (in pixel and frame unit)
	private static HashMap<Integer, int[]> frameROIs;
	private static Roi[] cellROIArray;
	//private static ArrayList<Roi[]> cellRois;
	//protected static Roi[] cellRois;

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
		
		// get loaded ROIs from ROI Manager
		cellROIArray = loadPredefinedROIs (ParameterDialog.cellRoiPath, imp, 15); //minimum size 15 um2
		
		// get spot collection & setup trackmate based on spot ROIs
		SpotCollection roiSpots = spots_from_RoiManager(cellROIArray, imp);
		TrackMate trackmate = create_trackmate( roiSpots, imp );
		if (!process(trackmate)) return;
		
		System.out.println("trackmate: spot count: " + String.valueOf(trackmate.getModel().getSpots().getNSpots(true)));
	 	Model model = trackmate.getModel();
	 	IJ.log("      TrackMate completed successfully.");
	 	IJ.log(String.format("      Found %d spots in %d tracks.", model.getSpots().getNSpots(true), model.getTrackModel().nTracks(true)));
		
	 	FeatureModel fm = model.getFeatureModel();
	 	Collection<String> tf = fm.getTrackFeatures();
	 	for (String f : tf) {
	 		System.out.println("track feature: " + f);
	 	}
		
	 	
	 	/*
	 	model = correctLinks(model, imp, cellROIArray, ParameterDialog.minCost, ParameterDialog.maxCount, 
	 			ParameterDialog.rangeXY, ParameterDialog.rangeT,
	 			ParameterDialog.minSimScore);
	 	*/
	 			
		/*
		 *  Use TrackMate result to further analyze Fucci fluorescence and mitosis
		 */
		RoiManager rm = ROIUtility.prepareManager(true);	
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
					Roi spotRoi = (idx==-1) ? new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize) : cellROIArray[idx];
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
									if (segments.size()!=0)
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

	

	private Roi[] loadPredefinedROIs(String spotRoiPath, ImagePlus imp, double minRoiSize) {
		double start = System.currentTimeMillis();
		// prepare ROI Manager
		RoiManager rm = ROIUtility.prepareManager(true);
		rm.runCommand("Open", ParameterDialog.cellRoiPath);
		if (rm==null || rm.getCount()==0) {
			ROIUtility.restoreManager();
			return null;
		}
		
		ArrayList<Roi> roiList = new ArrayList<Roi>();
		int nROI = rm.getCount();
		
		for (int i=0; i<nROI; i++) {
			imp.setRoi(rm.getRoi(i), false);
			if (imp.getStatistics().area <= minRoiSize) continue;
			Roi r = rm.getRoi(i);
			int t = r.hasHyperStackPosition() ? r.getTPosition() : r.getPosition();
			r.setPosition(0, 0, t);
			roiList.add(r);	
		}
		
		nROI = roiList.size();
		Roi[] cellROIArray = new Roi[nROI];
		//HashMap<Integer, ArrayList<Roi>> cellRois = new HashMap<Integer, ArrayList<Roi>>();
		int tPrev = 1; int fStart = 0; int fEnd = 0;
		frameROIs = new HashMap<Integer, int[]>();
		for (int i=0; i<nROI; i++) {
			int tCurrent = roiList.get(i).getTPosition();
			if (tCurrent != tPrev) {
				frameROIs.put(tPrev, new int[] {fStart, fEnd});
				// update ROI index and tIndex to new frame
				fStart = i;
				tPrev = tCurrent;
			}
			
			cellROIArray[i] = roiList.get(i);

			fEnd = i;
			if (i == nROI-1) { frameROIs.put(tPrev, new int[] {fStart, fEnd}); }
			//ArrayList<Roi> temp = cellRois.get(t);
			//if (temp==null) temp = new ArrayList<Roi>();
			//temp.add(cellROIArray[i]);
			//cellRois.put(t, temp);
		}
		ROIUtility.restoreManager();
		double duration = ( System.currentTimeMillis() - start ) / 1000;
		System.out.println("get ROI into hashmap takes " + duration + " seconds.");
		return cellROIArray;
	}



	private Model correctLinks(Model model, ImagePlus imp, Roi[] rois, double minCost, double maxCount, double rangeXY, double rangeT, double minSimScore) {
		// get ROI statistics: area, X, Y, T, channel mean, channel stdDev
		// area-distribution, XYT-range, mean-distribution, stdDev-distribution
		double start = System.currentTimeMillis();
		//HashMap<String, double[]> map = getROIStats (imp, cellRois);
		model.beginUpdate();
		
		int nROI = rois.length;
		HashMap<String, double[]> map = new HashMap<String, double[]>();
		double[] area = new double[nROI];
		double[] X = new double[nROI];	double[] Y = new double[nROI];	double[] T = new double[nROI];
		double[] major = new double[nROI];	double[] minor = new double[nROI];
		double[] C1mean = new double[nROI]; double[] C1stdDev = new double[nROI];
		double[] C2mean = new double[nROI]; double[] C2stdDev = new double[nROI];
		double[] C3mean = new double[nROI]; double[] C3stdDev = new double[nROI];
		for (int c=0; c<3; c++) {
			imp.setPositionWithoutUpdate(c+1, imp.getZ(), imp.getT());
			for (int i=0; i<nROI; i++) {
				imp.setRoi(rois[i], false);
				ImageStatistics stats = imp.getAllStatistics();
				if (c==0) {
					area[i] = stats.area;
					X[i] = stats.xCentroid; Y[i] = stats.yCentroid; T[i] = (double) rois[i].getTPosition();
					major[i] = stats.major; minor[i] = stats.minor;
					C1mean[i] = stats.mean; C1stdDev[i] = stats.stdDev;
				} else if (c==1) {
					C2mean[i] = stats.mean; C2stdDev[i] = stats.stdDev;
				} else { C3mean[i] = stats.mean; C3stdDev[i] = stats.stdDev;}
			}
		}
		// get normalization parameters;
		double[] areaMinMax = StatisticUtility.getMinMax ( area );
		double[] XMinMax = StatisticUtility.getMinMax (X);
		double[] YMinMax = StatisticUtility.getMinMax (Y);
		double[] TMinMax = StatisticUtility.getMinMax (T);
		
		double[] majorMinMax = StatisticUtility.getMinMax ( major );
		double[] minorMinMax = StatisticUtility.getMinMax ( minor );
		
		double[] C1meanMinMax = StatisticUtility.getMinMax ( C1mean );
		double[] C1stdDevMinMax = StatisticUtility.getMinMax ( C1stdDev );

		double[] C2meanMinMax = StatisticUtility.getMinMax ( C2mean );
		double[] C2stdDevMinMax = StatisticUtility.getMinMax ( C2stdDev );

		double[] C3meanMinMax = StatisticUtility.getMinMax ( C3mean );
		double[] C3stdDevMinMax = StatisticUtility.getMinMax ( C3stdDev );

		// get normalized values;
		for (int i=0; i<nROI; i++) {
			area[i] 	= StatisticUtility.normalizeDataByRange(area[i], areaMinMax[0], areaMinMax[1] );
			X[i]		= StatisticUtility.normalizeDataByRange(X[i], XMinMax[0], XMinMax[1] );
			Y[i]		= StatisticUtility.normalizeDataByRange(Y[i], YMinMax[0], YMinMax[1] );
			T[i]		= StatisticUtility.normalizeDataByRange(T[i], TMinMax[0], TMinMax[1] );
			major[i]	= StatisticUtility.normalizeDataByRange(major[i], majorMinMax[0], majorMinMax[1] );
			minor[i]	= StatisticUtility.normalizeDataByRange(minor[i], minorMinMax[0], minorMinMax[1] );
			C1mean[i]	= StatisticUtility.normalizeDataByRange(C1mean[i], C1meanMinMax[0], C1meanMinMax[1] );
			C1stdDev[i]	= StatisticUtility.normalizeDataByRange(C1stdDev[i], C1stdDevMinMax[0], C1stdDevMinMax[1] );
			C2mean[i]	= StatisticUtility.normalizeDataByRange(C2mean[i], C2meanMinMax[0], C2meanMinMax[1] );
			C2stdDev[i]	= StatisticUtility.normalizeDataByRange(C2stdDev[i], C2stdDevMinMax[0], C2stdDevMinMax[1] );
			C3mean[i]	= StatisticUtility.normalizeDataByRange(C3mean[i], C3meanMinMax[0], C3meanMinMax[1] );
			C3stdDev[i]	= StatisticUtility.normalizeDataByRange(C3stdDev[i], C3stdDevMinMax[0], C3stdDevMinMax[1] );
		}
		map.put( "area", area );	map.put( "X", X );	map.put( "Y", Y );	map.put( "T", T );
		map.put( "major", major );	map.put( "minor", minor );
		map.put( "C1mean", C1mean );	map.put( "C1stdDev", C1stdDev );
		map.put( "C2mean", C2mean );	map.put( "C2stdDev", C2stdDev );
		map.put( "C3mean", C3mean );	map.put( "C3stdDev", C3stdDev );

		double duration = (System.currentTimeMillis() - start) / 1000;
		System.out.println("get statistics: " + duration + " seconds");
		
		start = System.currentTimeMillis();
		
		SpotCollection spots = model.getSpots();
		int nSpot = spots.getNSpots(true);
		System.out.println("debug 524 nSpot : " + nSpot);
		//Iterator<Spot> spotIter = spots.iterator(true);
		TrackModel tm = model.getTrackModel();
		
		//TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();
		//double pixelSize = imp.getCalibration().pixelWidth;
		//int nSource = neighborIndex.predecessorsOf(spot).size();		
		int[][] spotID = new int[nSpot][3];	// [current spot, source spot, link cost]
		int iter = -1;
		
		Set<DefaultWeightedEdge> edgeSet = tm.edgeSet();
		Iterator<DefaultWeightedEdge> edgeIter = edgeSet.iterator();
		while (edgeIter.hasNext()) {
			iter++;
			DefaultWeightedEdge edge = edgeIter.next();
			Spot target = tm.getEdgeTarget(edge);
			Spot source = tm.getEdgeSource(edge);
			spotID[iter][0] = target.ID();
			spotID[iter][1] = source.ID(); 
			spotID[iter][2] = (int) tm.getEdgeWeight(edge);
		
		
		}
		
		/*
		while (spotIter.hasNext()) {
			iter++;
			Spot spot = spotIter.next();
			System.out.println("debug spot : " + spot.ID());
			//double x = spot.getFeature("POSITION_X") * pixelSize; //x = spot.getDoublePosition(0);
			//double y = spot.getFeature("POSITION_Y") * pixelSize;
			//double t = spot.getFeature("FRAME") + 1;
			
			//spotID[iter][0] = getRoiIndex(rois, x, y, t);
			spotID[iter][0] = spot.ID();
			spotID[iter][1] = -1; spotID[iter][2] = -1;
			
			List<Spot> sourceSpotList = neighborIndex.predecessorsOf(spot);
			if (sourceSpotList==null || sourceSpotList.size()!=1) continue;
			Spot sourceSpot = sourceSpotList.get(0);
			//double x2 = sourceSpot.getFeature("POSITION_X") * pixelSize; //x = spot.getDoublePosition(0);
			//double y2 = sourceSpot.getFeature("POSITION_Y") * pixelSize;
			//double t2 = sourceSpot.getFeature("FRAME") + 1;
			
			spotID[iter][1] = sourceSpot.ID();
			//spotID[iter][1] = getRoiIndex(rois, x2, y2, t2);
			
			spotID[iter][2] = (int) tm.getEdgeWeight(tm.getEdge(sourceSpot, spot));
			
		}
		*/

		// get high cost list
		ArrayList<Integer> costList = sortByCost (spotID);
		ArrayList<Integer> highCostList = getHighCostIndex (spotID, costList, minCost, maxCount);
		System.out.println("high cost list size: " + highCostList.size());
		duration = (System.currentTimeMillis() - start) / 1000;
		System.out.println("get high cost spots: " + duration + " seconds");
		/*
		for (int i=0; i<10; i++) {
			int idx = costList.get(i);
			println(idx + " : " + spotID[idx] + ", cost: " + cost[idx]);
		}
		*/
			
		// for each high cost spots, find its possible correct target:
		//int iter = 0;
		start = System.currentTimeMillis();
		for (int i=0; i<highCostList.size(); i++) {
			int index = highCostList.get(i);
			int oldTargetID = spotID[index][0];
			int sourceID = spotID[index][1];
			Spot sourceSpot = spots.search(sourceID);
			Spot oldTargetSpot = spots.search(oldTargetID);
			DefaultWeightedEdge oldEdge = tm.getEdge(sourceSpot, oldTargetSpot);
			if (oldEdge == null) continue;
			
			//int sourceRoiIndex = getSourceROIIndex (spotID, sourceSpotID, index);
			//println("\n\nsource ROI index: " + sourceRoiIndex);
			if (sourceID==-1) continue;
			
			//int maybeTargetIndex = getPossibleTarget (imp, rois, sourceID, map, rangeXY, rangeT, minSimScore);
			
			int maybeTargetSpotID = getPossibleTargetSpot (imp, spots, sourceID,  frameROIs, rois, map, rangeXY, rangeT, minSimScore);
			Spot newTargetSpot = spots.search(maybeTargetSpotID);
			if (maybeTargetSpotID == oldTargetID) {
				//println("	same target as before.");
			} else if (maybeTargetSpotID != -1) {
				model.removeEdge(oldEdge);
				model.addEdge(sourceSpot, newTargetSpot, minCost*0.95);
				// search for new source of the old target
				//int maybeSourceIndex = getPossibleSource (imp, rois, index, map, searchRange, minSimScore);
				
				//int maybeSourceSpotID = getPossibleSourceSpot (imp, spots, sourceID,  frameROIs, rois, map, rangeXY, rangeT, minSimScore);
				//Spot newSourceSpot = spots.search(maybeSourceSpotID);
				//if (maybeSourceSpotID != -1) {
				//	model.addEdge(newSourceSpot, oldTargetSpot, minCost*0.95);
				//}
			} else {
				//println("	target not found.");
			}	
		}
		//model.createFeatureModel();
		model.endUpdate();
		return model;
	}
	
	
	// sort cost array, large cost first,
	public ArrayList<Integer> sortByCost (int[][] spotID) { //int[] cost = spotID[][2];
		ArrayList<Integer> indices = new ArrayList<Integer>();
		for (int i=0; i<spotID.length; i++) { indices.add(i); }
		Collections.sort(indices, new Comparator<Integer>() {
			@Override
			public int compare(Integer idx1, Integer idx2) {
				return (int) ( spotID[idx2][2] - spotID[idx1][2] );
		}});
		return indices;
	}
	
	// filter cost by a certain threshold
	public ArrayList<Integer> getHighCostIndex (int[][] spotID, ArrayList<Integer> costList, double threshold, double maxCount) {
		ArrayList<Integer> highCostList = new ArrayList<Integer>();
		for (int i=0; i<costList.size(); i++) {
			if (spotID[costList.get(i)][2]>threshold && highCostList.size()<maxCount) { highCostList.add(costList.get(i)); }
			else break;
		}
		return highCostList;
	}
	
	// locate most similar ROI in the next 2 frames
	public int getPossibleTarget (ImagePlus imp, Roi[] rois, int sourceIndex, HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
		if (rois==null || rois.length<=sourceIndex) return -1;
		double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
		double[] center = rois[sourceIndex].getContourCentroid();
		double x0 = center[0]; double y0 = center[1];
		double t0 = (double) rois[sourceIndex].getTPosition();
		double[] value1 = StatisticUtility.getMeasurements ( imp, sourceIndex, map );
		//println("	value1: " + value1);
		double maxSim = 0; int targetIndex = -1;
		
		for (int i=0; i<rois.length; i++) {
			// range control
			center = rois[i].getContourCentroid();
			double x1 = center[0]; double y1 = center[1];
			if (x1<x0-dx || x1>x0+dx) continue;
			if (y1<y0-dy || y1>y0+dy) continue;
			if (rois[i].getTPosition()<=t0 || rois[i].getTPosition()>t0+dt) continue;
			
			
			double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
			//double simScore = getCosineSimilarity (value1, value2);
			double simScore = StatisticUtility.getJSD (value1, value2);
			if (simScore<maxSim) continue;
			maxSim = simScore;
			targetIndex = i;
		}
		//println("	max similarity score: " + maxSim);
		if (maxSim < minSimScore) targetIndex = -1;
		return targetIndex;
	}
	public int getPossibleTargetSpot (ImagePlus imp, SpotCollection spots, int sourceSpotID, 
			HashMap<Integer, int[]> frameROIs, Roi[] rois,
			HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
		//if (rois==null) return -1;
		double pixelSize = imp.getCalibration().pixelWidth;
		int targetIndex = -1;
		Spot spot = spots.search(sourceSpotID);
		double t = spot.getFeature("FRAME") + 1;
		//ArrayList<Roi> frameROI = frameROIs.get(t);
		//int sourceRoiIndex = getRoiIndex (frameROI, spot, pixelSize);
		int sourceRoiIndex = getRoiIndex (rois, spot, pixelSize);
		
		double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
		double[] center = rois[sourceRoiIndex].getContourCentroid();
		double x0 = center[0]; double y0 = center[1];
		double t0 = (double) rois[sourceRoiIndex].getTPosition();
		double[] value1 = StatisticUtility.getMeasurements ( imp, sourceRoiIndex, map );
		double maxSim = 0;
		
		int[] fStart = frameROIs.get( (int) (t0+1) );
		int[] fEnd = frameROIs.get( (int) (t0+dt) );
		for (int i=fStart[0]; i<=fEnd[1]; i++) {
			// range control
			center = rois[i].getContourCentroid();
			double x1 = center[0]; double y1 = center[1];
			if (x1<x0-dx || x1>x0+dx) continue;
			if (y1<y0-dy || y1>y0+dy) continue;
			
			double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
			//double simScore = getCosineSimilarity (value1, value2);
			double simScore = StatisticUtility.getJSD (value1, value2);
			if (simScore<maxSim) continue;
			maxSim = simScore;
			targetIndex = i;
		}
		//println("	max similarity score: " + maxSim);
		if (maxSim < minSimScore) targetIndex = -1;
		
		return getSpotIDFromRoi(rois, targetIndex, spots, pixelSize);		
		
	}
	// locate most similar ROI in the previous frames
	public int getPossibleSource (ImagePlus imp, Roi[] rois, int targetIndex, HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
		if (rois==null || rois.length<=targetIndex) return -1;
		int maxT = imp.getNFrames();
		double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
		double[] center = rois[targetIndex].getContourCentroid();
		double x0 = center[0]; double y0 = center[1];
		double t0 = (double) rois[targetIndex].getTPosition();
		double[] value1 = StatisticUtility.getMeasurements ( imp, targetIndex, map );
		//println("	value1: " + value1);
		double maxSim = 0; int sourceIndex = -1;
		
		for (int i=0; i<rois.length; i++) {
			// range control
			center = rois[i].getContourCentroid();
			double x1 = center[0]; double y1 = center[1];
			if (x1<x0-dx || x1>x0+dx) continue;
			if (y1<y0-dy || y1>y0+dy) continue;
			if (rois[i].getTPosition()>=t0 || rois[i].getTPosition()<t0-dt) continue;
			
			
			double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
			//double simScore = getCosineSimilarity (value1, value2);
			double simScore = StatisticUtility.getJSD (value1, value2);
			if (simScore<maxSim) continue;
			maxSim = simScore;
			sourceIndex = i;
		}
		if (maxSim < minSimScore) {
			sourceIndex = -1; 
			//println("	max similarity score not found");
		}
		//else println("	max similarity score: " + maxSim);
		return sourceIndex;
	}
	
	public int getPossibleSourceSpot (ImagePlus imp, SpotCollection spots, int targetSpotID, 
			HashMap<Integer, int[]> frameROIs, Roi[] rois,
			HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
		//if (rois==null) return -1;
		double pixelSize = imp.getCalibration().pixelWidth;
		int sourceIndex = -1;
		Spot spot = spots.search(targetSpotID);
		double t = spot.getFeature("FRAME") + 1;
		//ArrayList<Roi> frameROI = frameROIs.get(t);
		//int sourceRoiIndex = getRoiIndex (frameROI, spot, pixelSize);
		int targetRoiIndex = getRoiIndex (rois, spot, pixelSize);
		
		double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
		double[] center = rois[targetRoiIndex].getContourCentroid();
		double x0 = center[0]; double y0 = center[1];
		double t0 = (double) rois[targetRoiIndex].getTPosition();
		double[] value1 = StatisticUtility.getMeasurements ( imp, targetRoiIndex, map );
		double maxSim = 0;
		
		int[] fStart = frameROIs.get( (int) (t0-dt) );
		int[] fEnd = frameROIs.get( (int) (t0-1) );
		for (int i=fStart[0]; i<=fEnd[1]; i++) {
			// range control
			center = rois[i].getContourCentroid();
			double x1 = center[0]; double y1 = center[1];
			if (x1<x0-dx || x1>x0+dx) continue;
			if (y1<y0-dy || y1>y0+dy) continue;
			
			double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
			//double simScore = getCosineSimilarity (value1, value2);
			double simScore = StatisticUtility.getJSD (value1, value2);
			if (simScore<maxSim) continue;
			maxSim = simScore;
			sourceIndex = i;
		}
		//println("	max similarity score: " + maxSim);
		if (maxSim < minSimScore) sourceIndex = -1;
		
		return getSpotIDFromRoi(rois, sourceIndex, spots, pixelSize);		
		
	}

	private SpotCollection spots_from_RoiManager(Roi[] rois, ImagePlus imp) {
		if (imp==null || rois==null || rois.length==0) return null;
		int nROI = rois.length;
		double pixelSize = imp.getCalibration().pixelWidth;
		double pixelArea = pixelSize * pixelSize;
		double frameInterval = imp.getCalibration().frameInterval;
		SpotCollection spots = new SpotCollection();
	 
	    for (int i=0; i<nROI; i++) {
	    	Roi roi = rois[i];
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
			double quality = 0; // check this
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
	private TrackMate create_trackmate(SpotCollection spots, ImagePlus imp) {
	    /*
	    Creates a TrackMate instance configured to operated on the specified
	    ImagePlus imp with cell analysis stored in the specified ResultsTable
	    results_table.
	    */
	    Calibration cal = imp.getCalibration();
	    // TrackMate Model.
	    Model model = new Model();
	    model.setLogger( Logger.DEFAULT_LOGGER );
	    model.setPhysicalUnits( cal.getUnit(), cal.getTimeUnit() );
	    // Settings.
	    Settings settings = new Settings();
	    settings.setFrom( imp );
	    // Create the TrackMate instance.
	    //settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
	    TrackMate trackmate = new TrackMate( model, settings );
	     
	    // Add ALL the feature analyzers known to TrackMate, via providers. 
	    // They offer automatic analyzer detection, so all the  available feature analyzers will be added. 
	    // Some won't make sense on the binary image (e.g. contrast) but nevermind.
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
	    //SpotCollection spots = spots_from_RoiManager(cellRois, imp);
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
	
	private int getSpotIDFromRoi (Roi[] rois, int roiIndex, SpotCollection spots, double pixelSize) {
		if (rois==null || roiIndex<0 || rois.length<=roiIndex || spots==null) return -1;
		Roi roi = rois[roiIndex];
		int tPos = roi.getTPosition();
		Iterator<Spot>	iter = spots.iterator(tPos, false); // check frame position
		boolean first = true;
		while (iter.hasNext()) {
			Spot spot = iter.next();
			if (first) {
				//System.out.println("debug: 924 get spot index, first spot on frame " + tPos + " : " + spot.ID());
				first = false;
			}
			double x = spot.getFeature("POSITION_X") / pixelSize;
			double y = spot.getFeature("POSITION_Y") / pixelSize;
			
			if (roi.contains((int)x, (int)y)) {
				return spot.ID();
			}
		}
		return -1;
	}
	private int getRoiIndex (Roi[] rois, Spot spot, double pixelSize) {
		double x = spot.getFeature("POSITION_X") / pixelSize;
		double y = spot.getFeature("POSITION_Y") / pixelSize;
		double t = spot.getFeature("FRAME") + 1;
		return getRoiIndex (rois, x, y, t);
	}
	private int getRoiIndex (ArrayList<Roi> frameRois, Spot spot, double pixelSize) {
		double x = spot.getFeature("POSITION_X") / pixelSize;
		double y = spot.getFeature("POSITION_Y") / pixelSize;
		double t = spot.getFeature("FRAME") + 1;
		return getRoiIndex (frameRois, x, y, t);
	}
	private int getRoiIndex (double x, double y, double t) {
		return getRoiIndex (cellROIArray, x, y, t);
	}
	private int getRoiIndex (Roi[] rois, double x, double y, double t) {// x, y in pixel unit
		if (rois==null || rois.length==0) return -1;
		for (int i=0; i<rois.length; i++) {
			if (rois[i].getTPosition()!=t) continue;
			if (rois[i].contains((int)x, (int)y)) return i;
		}
		return -1;
	}
	private int getRoiIndex (ArrayList<Roi> rois, double x, double y, double t) {// x, y in pixel unit
		if (rois==null || rois.size()==0) return -1;
		for (int i=0; i<rois.size(); i++) {
			if (rois.get(i).getTPosition()!=t) continue;
			if (rois.get(i).contains((int)x, (int)y)) return i;
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
		
		
		
		FucciMitosisAnalysis4 fma = new FucciMitosisAnalysis4();
		fma.run(null);
		
		
	}
}
