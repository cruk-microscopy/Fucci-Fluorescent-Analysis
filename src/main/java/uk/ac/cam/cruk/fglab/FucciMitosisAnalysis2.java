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
public class FucciMitosisAnalysis2 implements PlugIn {
	
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
		
	/* 
	 * check 3rd party plugin installation
	 */
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
		/*
		if (!PluginUtility.pluginCheck("imagescience", PluginUtility.jarDir())) {
			installImageScience = PluginUtility.installImageScience();
			if (!installImageScience) {
				IJ.log("   \"Imagescience\" not installed!");
				IJ.log("   Consider installing it manually.");
				return;
			}
		}
		*/
		if (installTrackMateExtras || installImageScience) {
			IJ.log("   3rd party plugin installed.");
			IJ.log("   Restart Fiji to run the Fucci toolbox plugin.");
			return;
		}
		
	/* 
	 * get user input with parameter dialog
	 */
		/*
		ParameterDialog pd = new ParameterDialog();
		if (!pd.mainDialog())	return;
		*/
		if (!ParameterDialog.mainDialog())	return;
		
	// timing the start
		IJ.log(GetDateAndTime.getCurrentDate());
		IJ.log(GetDateAndTime.getCurrentTime());
		long start = GetDateAndTime.getCurrentTimeInMs();
		
	/*
	 *  load data as image plus
	 */
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
	/*
	 * Construct Weka	
	 */
		Classifier cls = null;
		if (ParameterDialog.doWeka) {
			try {
				cls = (Classifier) SerializationHelper.read(ParameterDialog.modelPath);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				IJ.log("Failed to load classifier. Will use default mitosis classifier instead.");
				ParameterDialog.doWeka = false;
				e.printStackTrace();
			}
		}
		ArrayList<Integer> featureChannels = null;
		ArrayList<Integer> statFeatures = null;	//statistical features: 
		ArrayList<String> classAttributes = null;
		if (ParameterDialog.doWeka) {
			try {
				featureChannels = WekaUtility.getFeatureChannelsFromModel(cls);
				statFeatures = WekaUtility.getObjFeatureFromModel(cls);
		    	classAttributes = WekaUtility.getClassAttributeFromModel(cls);
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			if (Collections.max(featureChannels)>numC) {
				IJ.error("Loaded classifier need more channels than the image being analyzed!");
				return;
			}
		}
		
		
			/*
			IJ.log("debug: featureChannels: "+String.valueOf(featureChannels));
			IJ.log("debug: statFeatures: "+String.valueOf(statFeatures));
			IJ.log("debug: classAttributes: "+String.valueOf(classAttributes));
			*/
	/*
	 * Construct Weka finish here !!!	
	 */
	/*
	 *  !!!Setup TrackMate!!!
	 */
		Settings settings = new Settings();
		settings.setFrom(imp);
		// Spot analyzer: we want the multi-C intensity analyzer.
		settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
		//settings.addSpotAnalyzerFactory(new SpotRadiusEstimatorFactory());
		// Spot detector
		settings.detectorFactory = new LogDetectorFactory();
		//settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
		//IJ.log(settings.detectorSettings);	//debug
		Map<String, Object> map = settings.detectorFactory.getDefaultSettings();
		map.put("TARGET_CHANNEL", ParameterDialog.targetChannel);
		map.put("RADIUS", ParameterDialog.spotRadius);
		map.put("THRESHOLD", ParameterDialog.qualityThreshold);	//dialog
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
		map.put("MAX_FRAME_GAP", ParameterDialog.frameGap);	//dialog
		map.put("LINKING_MAX_DISTANCE", ParameterDialog.linkingMax);
		map.put("GAP_CLOSING_MAX_DISTANCE", ParameterDialog.closingMax);
		settings.trackerSettings = map;
		// add link/edge analyzer
		settings.addEdgeAnalyzer(new EdgeTargetAnalyzer());
		// Run TrackMate and store data into Model
		Model model = new Model();
		model.setPhysicalUnits( imp.getCalibration().getUnit(), imp.getCalibration().getTimeUnit() );
		TrackMate trackmate = new TrackMate(model, settings);
		if (!(trackmate.checkInput()) || !(trackmate.process())) {
		    IJ.log("Could not execute TrackMate: " + trackmate.getErrorMessage());
		    return;
		}
		System.out.println("trackmate 1st: spot count: " + String.valueOf(trackmate.getModel().getSpots().getNSpots(true)));
		// trim spots !!!
		PointRoi[] tmRois = new PointRoi[numT];
		final NavigableSet< Integer > frames = trackmate.getModel().getSpots().keySet();
		for ( final int frame : frames ) {
			final int points = trackmate.getModel().getSpots().getNSpots( frame, true );
			final float[] ox = new float[ points ];
			final float[] oy = new float[ points ];
			final Iterable< Spot > iterable = trackmate.getModel().getSpots().iterable( frame, true );
			int index = 0;
			for ( final Spot spot : iterable ) {
				final double x = spot.getDoublePosition( 0 ) / dx;
				final double y = spot.getDoublePosition( 1 ) / dy;
				ox[ index ] = ( float ) x;
				oy[ index ] = ( float ) y;
				index++;
			}
			final PointRoi roi = new PointRoi( ox, oy, points );
			roi.setPosition(0, 0, frame+1);
			tmRois[frame] = roi;
			//roiManager.addRoi( roi );
		}
		ImagePlus impDup = new Duplicator().run(imp, 2, 2, 1, numZ, 1, numT);
		String method = "Triangle";
		IJ.run(impDup, "Convert to Mask", "method=["+method+"] background=Dark calculate black");
		IJ.run(impDup, "Median...", "radius=2.5 stack");
		IJ.run(impDup, "Watershed", "stack");
		// iterate through all time frames
		ArrayList<Roi> newSpotRois = new ArrayList<Roi>();
		for (int t=0; t<numT; t++) {
			// get all point ROIs at current frame
			Point[] points = tmRois[t].getContainedPoints();
			int nPoints = points.length;
			// get 2nd and 3rd channel image at current frame
			// generate mask of the two images: 2nd: Huang, 3rd: Otsu
			// generate object map from the mask
			impDup.setPositionWithoutUpdate(1, 1, t+1);
			//ipC = imp.getProcessor();
			//ipC.setAutoThreshold(method , true, ImageProcessor.NO_LUT_UPDATE);
			//bp = ipC.createMask();
			//new RankFilters().rank(bp, 2.5, RankFilters.MEDIAN);
			//new EDM().toWatershed(bp);
			ImageProcessor obj = BinaryImages.componentsLabeling(impDup.getProcessor(), 4, 16);
			
			// use the object map to merge point ROIs
			int nObj = (int) obj.getStats().max;
			List<Integer>[] pointInObj = (List<Integer>[]) new ArrayList[nObj];
			for (int i=0; i<nObj; i++) {
				pointInObj[i] = new ArrayList<Integer>();
			}
			ArrayList<Integer> mergeCandidateObj = new ArrayList<Integer>();
			for (int i=0; i<nPoints; i++) {
				int value = obj.get((int)points[i].x, (int)points[i].y);
				if (value==0) continue;
				pointInObj[value-1].add(i);
				// check if object id (value) had already contained in the list
				if (pointInObj[value-1].size() > 1)
					mergeCandidateObj.add(value-1);
			}
			//PointRoi newRoi = new PointRoi();
			ArrayList<Point> newPoints = new ArrayList<Point>();
			for (int i=0; i<nPoints; i++) {
				int value = obj.get((int)points[i].x, (int)points[i].y);
				if (!mergeCandidateObj.contains(value-1)) {
					PointRoi newRoi = new PointRoi(dx*points[i].getX(), dy*points[i].getY());
					newRoi.setPosition(0, 0, t+1);
					newSpotRois.add(newRoi);
				}
			}
			for (Integer idx : mergeCandidateObj) {
				double newX = 0; double newY = 0;
				int count = 0;
				for (Integer idx2 : pointInObj[idx]) {
					newX += points[idx2].getX();
					newY += points[idx2].getY();
					count++;
				}
				//System.out.println("merged position: t:" + (t+1) + " x:" + (dx*newX/(double)count) + " y:" + (dy*newY/(double)count));
				PointRoi newRoi = new PointRoi(dx*newX/(double)count, dy*newY/(double)count);
				newRoi.setPosition(0, 0, t+1);
				newSpotRois.add(newRoi);
			}	
		}
		
		
		model = new Model();
		model.setLogger( Logger.IJ_LOGGER );
		model.setPhysicalUnits( imp.getCalibration().getUnit(), imp.getCalibration().getTimeUnit() );
		 
		settings = new Settings();
	    settings.setFrom( imp );
	    // Spot analyzer: we want the multi-C intensity analyzer.
	 	settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
	    trackmate = new TrackMate( model, settings );
	    
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
		
		//settings.addSpotAnalyzerFactory(new SpotRadiusEstimatorFactory());
				
	    trackmate.getModel().getLogger().log( settings.toStringFeatureAnalyzersInfo() );
	    trackmate.computeSpotFeatures( true );
	    trackmate.computeEdgeFeatures( true );
	    trackmate.computeTrackFeatures( true );
	     
	    // Skip detection && get spots from results table.
		SpotCollection newSpots = spots_from_RoiManager(newSpotRois.toArray(), imp);
		model.setSpots(newSpots, false);
		
		// Spot detector
		settings.detectorFactory = new ManualDetectorFactory();
	    settings.detectorSettings = new HashMap<String, Object>();
	    settings.detectorSettings.put("RADIUS", ParameterDialog.spotRadius);
				
		// Spot tracker
		settings.trackerFactory = new SparseLAPTrackerFactory();
		settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		//map = LAPUtils.getDefaultLAPSettingsMap();
		settings.trackerSettings.put("ALLOW_TRACK_SPLITTING", true);
		settings.trackerSettings.put("ALLOW_TRACK_MERGING", false);
		settings.trackerSettings.put("MAX_FRAME_GAP", ParameterDialog.frameGap);	//dialog
		settings.trackerSettings.put("LINKING_MAX_DISTANCE", ParameterDialog.linkingMax);
		settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", ParameterDialog.closingMax);
		//settings.trackerSettings = map;
		// add link/edge analyzer
		//settings.addEdgeAnalyzer(new EdgeTargetAnalyzer());
		// Run TrackMate and store data into Model
		//Model model = new Model();
		//trackmate = new TrackMate(model, settings);
		
		
		//settings.detectorFactory = new ManualDetectorFactory();
	    //settings.detectorSettings = new HashMap<String, Object>();
	    //settings.detectorSettings.put("RADIUS", ParameterDialog.spotRadius);
	    
	    settings.initialSpotFilterValue = -1.0;
		//if (!(trackmate.checkInput()) || !(trackmate.process())) {
		//    IJ.log("Could not execute TrackMate: " + trackmate.getErrorMessage());
		//    return;
		//}
		
		
		// create a new trackmate for the trimmed spot ROIs
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
	 	
 	    if (!ok) {
		    IJ.log("Could not execute TrackMate: " + trackmate.getErrorMessage());
		    return;
		}

 	   System.out.println("trackmate 2nd: spot count: " + String.valueOf(trackmate.getModel().getSpots().getNSpots(true)));
 	  // model = trackmate.getModel();
		
 	   IJ.log("      TrackMate completed successfully.");
 	   IJ.log(String.format("      Found %d spots in %d tracks.", model.getSpots().getNSpots(true), model.getTrackModel().nTracks(true)));
		
		
	/*
	 *  !!!Setup TrackMate Finished here!!!
	 */
		

		
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
			ROIUtility.resetManager();
			RoiManager rm = RoiManager.getInstance2();
			ROIUtility.hideManager();
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
				isMitosis = false;
				Spot currentSpot = iter.next();
				String roiName = "ROI:" + String.format("%0"+String.valueOf(nZeros)+"d", ++roiCount) + ", ID:" + lineageID;
				double x = currentSpot.getFeature("POSITION_X");
				double y = currentSpot.getFeature("POSITION_Y");
				double t = currentSpot.getFeature("FRAME") + 1;
				//double estimatedDiameter = currentSpot.getFeature("ESTIMATED_DIAMETER");
				double d = ParameterDialog.spotRadius*2;
				// generate circle selection and add to RoiManager
				Roi spotRoi = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
				spotRoi.setPosition(0, 1, (int)t);
				// use the circle ROI to crop around original image and do mitosis check
				imp.setPositionWithoutUpdate(spotRoi.getCPosition(), spotRoi.getZPosition(), spotRoi.getTPosition());
				imp.setRoi(spotRoi, false);
				// crop image around spot, process updated spot Roi to the cropped image
				impCrop = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), imp.getT(), imp.getT());
				Roi croppedRoi = WekaUtility.cropRoi(imp, (Roi)spotRoi.clone());
				croppedRoi.setLocation(0,0);
				//!!! create Weka testing dataset using the image and roi pair
				
				if (ParameterDialog.doWeka) {
					Instances dataTest = WekaUtility.createTestingInstance(impCrop, croppedRoi, classAttributes, featureChannels, statFeatures);
					int classIdx=dataTest.numAttributes()-1;
		    		dataTest.setClassIndex(classIdx);	
		    		double[] probs;
					try {
						//label = cls.classifyInstance(dataTest.instance(0));
						probs = cls.distributionForInstance(dataTest.instance(0));
					} catch (Exception e1) {
						e1.printStackTrace();
						continue;
					}
					if (probs[0]>ParameterDialog.minMitoProb) {
						isMitosis = true;
						++nMitosis;
						//mitosisSpots.add(currentSpot);
						//if (!mitosisTrackIDs.contains(trackID)) {
						//	mitosisTrackIDs.add(trackID);
						//}
					}
				}
				
				rm.add(imp, spotRoi, currentSpot.ID());	// add roi with imp to RoiManager
				if (isMitosis) {
					roiName += ", mitosis";
					//mitosisRoiIDs.add(rm.getCount()-1);
				}
				rm.rename(rm.getCount()-1, roiName);	
				seg.add(rm.getCount()-1);
				
				// check segment condition
				int endID = -1;
				List<Spot> predecessor = neighborIndex.predecessorListOf(currentSpot);
				if (predecessor.size() == 1) endID = predecessor.get(0).ID();

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
				table.addValue( "Target Spot number", nTarget );
				table.addValue( "mitosis", isMitosis?"yes":"no" );
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
			try {
				table.saveAs(savePath + TABLE_NAME + " (" + imp.getTitle() + ").csv");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		// save all track spots in Roimanager to roiset.zip
		Roi[] trackSpotRois = rm.getRoisAsArray();
		if (ParameterDialog.saveROI) {			
			rm.runCommand("Save", savePath + CELL_ROI_NAME + " (" + imp.getTitle() + ").zip");
		}
		
		// save single track / mitosis ROIs to folder
		/*
		if (ParameterDialog.saveSingleCell || ParameterDialog.saveMitosisTrack) {
			int minSize = (int)(3600*ParameterDialog.minLength/imp.getCalibration().frameInterval);
			String singleTrackSavePath = savePath + SINGLE_TRACK_NAME + " (" + imp.getTitle() + ")" + File.separator;
			String mitoTrackSavePath = savePath + MITOSIS_TRACK_NAME + " (" + imp.getTitle() + ")" + File.separator;
			if (ParameterDialog.saveSingleCell) {
				if (!new File(singleTrackSavePath).exists()) {
					if (!new File(singleTrackSavePath).mkdirs())
						singleTrackSavePath = savePath;
				}
			}
			if (ParameterDialog.saveMitosisTrack) {
				if (!new File(mitoTrackSavePath).exists()) {
					if (!new File(mitoTrackSavePath).mkdirs())
						mitoTrackSavePath = savePath;
				}
			}
			
			Iterator<Map.Entry<String, ArrayList<Integer>>> itr = singleCells.entrySet().iterator();
			while(itr.hasNext()) {
				if (itr.next().getValue().size()<minSize)
					itr.remove();
			}
			itr = singleCells.entrySet().iterator();
			
			while(itr.hasNext()) {
				@SuppressWarnings("rawtypes")
				Map.Entry pair = (Map.Entry)itr.next();
				ArrayList<Integer> roiIdList = (ArrayList<Integer>) pair.getValue();
				//if (roiIdList.size()<minSize) continue;
				int[] roiIdArray = new int[roiIdList.size()];
				for (int i=0; i<roiIdList.size(); i++) {
					roiIdArray[i] = roiIdList.get(i).intValue();
				}
				rm.setSelectedIndexes(roiIdArray);
				
				if (ParameterDialog.saveSingleCell)
					rm.runCommand("save selected", singleTrackSavePath + "cell_"+pair.getKey()+".zip");
				
				ArrayList<Integer> mitoRois = (ArrayList<Integer>) roiIdList.clone();
				mitoRois.retainAll(mitosisRoiIDs);
				if (mitoRois.size()!=0 && ParameterDialog.saveMitosisTrack)
					rm.runCommand("save selected", mitoTrackSavePath + "cell_"+pair.getKey()+".zip");
			}
		}
		*/
		// ToDo:	add mitosis spreadsheet data with trackstack;
		// 			add mitosis summary spreadsheet
		//			identify 1st, 2nd, 3rd mitosis
		//			filter bad tracks based on channel intensity change
		
	/*
	 * Finish up:
	 * 	restore RoiManager, !!! restore ROIs initially in the manager;
	 * 						!!! or display all track spots as ROI
	 *  display processed image,
	 *  timing the duration
	 */
		/*
		rm.reset();
		for (Roi trackSpotRoi : trackSpotRois) {
			rm.addRoi(trackSpotRoi);
		}
		*/
		rm.setVisible(true);
		imp.show();
		if (ParameterDialog.viewInGUI) display_results_in_GUI(trackmate, imp);
		long end = GetDateAndTime.getCurrentTimeInMs();
		String duration = GetDateAndTime.getDuration(end-start);
		IJ.log("Fucci mitosis analysis completed successfully after : " + duration);
	}


	private SpotCollection spots_from_RoiManager(Object[] objects, ImagePlus sourceImg) {
		int nSpots = objects.length;
		SpotCollection spots = new SpotCollection();
	 
	    for (int i=0; i<nSpots; i++) {
	    	Roi spotRoi = (Roi) objects[i];
	    	//double[] center = spotRoi.getContourCentroid();
	    	//double x = spotRoi.getXBase() * sourceImg.getCalibration().pixelWidth;
	        //double y = spotRoi.getYBase() * sourceImg.getCalibration().pixelWidth;
	    	double x = spotRoi.getXBase();
	        double y = spotRoi.getYBase();
	    	int frame = spotRoi.getTPosition();
	    	double area = spotRoi.getStatistics().area;
	    	double t = (frame - 1) * sourceImg.getCalibration().frameInterval;
			//double radius = Math.sqrt( area / Math.PI) * sourceImg.getCalibration().pixelWidth;
			double radius = Math.sqrt( area / Math.PI);
			double quality = (double)i;
	        Spot spot = new Spot(x, y, 0, radius, quality);
	        spot.putFeature( "POSITION_T", t);
	        spots.add( spot, (int)(frame-1));
	    }
	    return spots;
	}

	private TrackMate create_trackmate(Object[] spotRois, ImagePlus imp) {
	    /*
	    Creates a TrackMate instance configured to operated on the specified
	    ImagePlus imp with cell analysis stored in the specified ResultsTable
	    results_table.
	    */
	     
	    Calibration cal = imp.getCalibration();
	     
	    // TrackMate.
	     
	    // Model.
	    Model model = new Model();
	    model.setLogger( Logger.IJ_LOGGER );
	    model.setPhysicalUnits( cal.getUnit(), cal.getTimeUnit() );
	     
	    // Settings.
	    Settings settings = new Settings();
	    settings.setFrom( imp );
	     
	    // Create the TrackMate instance.
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
	    SpotCollection spots = spots_from_RoiManager(spotRois, imp);
	    model.setSpots( spots, false );
	     
	    // Configure detector. We put nothing here, since we already have the spots 
	    // from previous step.
	    settings.detectorFactory = new ManualDetectorFactory();
	    settings.detectorSettings = new HashMap<String, Object>();
	    settings.detectorSettings.put( "RADIUS" , 3.0d);
	    // Configure tracker
	    settings.trackerFactory = new SparseLAPTrackerFactory();
	    settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
	    settings.trackerSettings.put( "LINKING_MAX_DISTANCE" , 25.0d);
	    settings.trackerSettings.put( "GAP_CLOSING_MAX_DISTANCE" , 25.0d);
	    settings.trackerSettings.put( "MAX_FRAME_GAP" , 3);
	    settings.trackerSettings.put( "ALLOW_TRACK_SPLITTING" ,  true);
		settings.trackerSettings.put( "ALLOW_TRACK_MERGING" ,  false);
	    settings.initialSpotFilterValue = -1.0;

	    return trackmate;
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
		
		
		
		FucciMitosisAnalysis2 fma = new FucciMitosisAnalysis2();
		fma.run(null);
		
		
	}
}
