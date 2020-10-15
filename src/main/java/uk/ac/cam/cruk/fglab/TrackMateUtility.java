package uk.ac.cam.cruk.fglab;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.detection.LogDetectorFactory;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.extra.spotanalyzer.SpotMultiChannelIntensityAnalyzerFactory;
import fiji.plugin.trackmate.features.edges.EdgeTargetAnalyzer;
import fiji.plugin.trackmate.features.spot.SpotRadiusEstimatorFactory;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.io.TmXmlWriter;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.TrackMateModelView;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;

public class TrackMateUtility {

	private ImagePlus imp;
	private Roi[] cellRois;
	
	private int targetChannel = 3;	// target channel for spot detection / cell ROI generation
	private double spotRadius = 6.5d;	// spot radius for LoG spot detection
	private double qualityThreshold = 10.0d;	// quality threshold for LoG spot detection
	private double linkingMax = 55.0d;	// maximum spot linking / gap closing distance
	private double closingMax;
	private int frameGap = 3;	//
	
	public TrackMateUtility (ImagePlus imp, ParameterDialog params) {
		if (imp==null || params==null) return;
		this.imp = imp;
		this.cellRois = params.cellRois;
		this.spotRadius = params.spotRadius;
		this.targetChannel = params.targetChannel;
		this.qualityThreshold = params.qualityThreshold;
		this.linkingMax = params.linkingMax;
		this.closingMax = params.closingMax;
		this.frameGap = params.frameGap;
	}
	
	public TrackMate trackmateWithLogSpot () {
		Settings settings = new Settings();
		settings.setFrom( this.imp );
		// Spot analyzer: we want the multi-C intensity analyzer.
		settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
		settings.addSpotAnalyzerFactory(new SpotRadiusEstimatorFactory());
		// Spot detector
		settings.detectorFactory = new LogDetectorFactory();
		//settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
		//IJ.log(settings.detectorSettings);	//debug
		Map<String, Object> map = settings.detectorFactory.getDefaultSettings();
		map.put("TARGET_CHANNEL", this.targetChannel);
		map.put("RADIUS", this.spotRadius);
		map.put("THRESHOLD", this.qualityThreshold);	//dialog
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
		map.put("MAX_FRAME_GAP", this.frameGap);	//dialog
		map.put("LINKING_MAX_DISTANCE", this.linkingMax);
		map.put("GAP_CLOSING_MAX_DISTANCE", this.closingMax);
		settings.trackerSettings = map;
		// add link/edge analyzer
		settings.addEdgeAnalyzer(new EdgeTargetAnalyzer());
		// Run TrackMate and store data into Model
		Model model = new Model();
		model.setPhysicalUnits( imp.getCalibration().getUnit(), imp.getCalibration().getTimeUnit() );
		TrackMate trackmate = new TrackMate(model, settings);
		
		if (!(trackmate.checkInput()) || !(trackmate.process())) {
		    IJ.error("Could not execute TrackMate!");
		    System.out.println("TrackMate error: " + trackmate.getErrorMessage());
		    return null;
		}
		return trackmate;
	}
	
	public TrackMate trackmateWithCellRoi () {
	    /*
	    Creates a TrackMate instance configured to operated on the specified
	    ImagePlus imp with cell analysis stored in the specified ResultsTable
	    results_table.
	    */
		
		SpotCollection spots = spots_from_RoiManager(this.imp, this.cellRois);
		
		
	    Calibration cal = this.imp.getCalibration();
	    // TrackMate Model.
	    Model model = new Model();
	    model.setLogger( Logger.DEFAULT_LOGGER );
	    model.setPhysicalUnits( cal.getUnit(), cal.getTimeUnit() );
	    // Settings.
	    Settings settings = new Settings();
	    settings.setFrom( this.imp );
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
	    settings.detectorSettings.put( "RADIUS" , this.spotRadius);
	    // Configure tracker
	    settings.trackerFactory = new SparseLAPTrackerFactory();
	    settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
	    settings.trackerSettings.put( "LINKING_MAX_DISTANCE" , this.linkingMax);
	    settings.trackerSettings.put( "GAP_CLOSING_MAX_DISTANCE" , this.closingMax);
	    settings.trackerSettings.put( "MAX_FRAME_GAP" , this.frameGap);
	    settings.trackerSettings.put( "ALLOW_TRACK_SPLITTING" ,  true);
		settings.trackerSettings.put( "ALLOW_TRACK_MERGING" ,  false);
	    settings.initialSpotFilterValue = -1.0;
	    
	    if ( !(process(trackmate)) ) {
		    IJ.error("Could not execute TrackMate!");
		    System.out.println("TrackMate error: " + trackmate.getErrorMessage());
		    return null;
		}

	    return trackmate;
	}
	
	private SpotCollection spots_from_RoiManager(ImagePlus imp, Roi[] rois) {
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
	
	
	public static void saveTrackmateModel (TrackMate trackmate, String savePath) {
		if (savePath.toLowerCase().endsWith(".xml")) savePath += ".xml";
		File xmlFile = new File(savePath);
		Logger logger = trackmate.getModel().getLogger();
		TmXmlWriter writer = new TmXmlWriter(xmlFile, logger);
		writer.appendLog(logger.toString());
		writer.appendModel(trackmate.getModel());
		writer.appendSettings(trackmate.getSettings());
		try {
			writer.writeToFile();
		} catch (IOException e) {
			System.out.println("Can not save track model! Current tracking result will not be modifiable later!");
			e.printStackTrace();
		}
	}
	
	
	public static void display_results_in_GUI ( ImagePlus imp, TrackMate trackmate ) {
	    TrackMateGUIController gui = new TrackMateGUIController( trackmate );
	    // Link displayer and GUI.
	    Model model = trackmate.getModel();
	    SelectionModel selectionModel = new SelectionModel( model);
	    HyperStackDisplayer displayer = new HyperStackDisplayer( model, selectionModel, imp );
	    gui.getGuimodel().addView( displayer );
	    Map<String, Object> displaySettings = new HashMap<>();
	    displaySettings = gui.getGuimodel().getDisplaySettings();
		displaySettings.put( TrackMateModelView.KEY_SPOTS_VISIBLE, false );
		displaySettings.put( TrackMateModelView.KEY_TRACKS_VISIBLE, true );
		displaySettings.put( TrackMateModelView.KEY_TRACK_DISPLAY_MODE, TrackMateModelView.TRACK_DISPLAY_MODE_LOCAL );
		displaySettings.put( TrackMateModelView.KEY_TRACK_DISPLAY_DEPTH, 6 ); // display 6 frames
	    
	    for (String key : displaySettings.keySet()) {
	        displayer.setDisplaySettings( key, displaySettings.get( key ) );
	    }
	    displayer.render();
	    displayer.refresh();
	     
	    gui.setGUIStateString( "ConfigureViews" );
	}
	
	
}
