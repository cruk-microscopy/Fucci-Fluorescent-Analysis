package uk.ac.cam.cruk.fglab;

import java.awt.Panel;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JButton;

import org.scijava.prefs.DefaultPrefService;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.detection.LogDetectorFactory;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;

public class MitosisDetector implements PlugIn {

	protected static ImagePlus impOri = null;
	
	protected static int hGEMChannel = 1;
	protected static int hCdt1Channel = 2;
	protected static int dapiChannel = 3;
	
	protected static int targetChannel = 3;
	protected static double spotRadius = 10;
	protected static double qualityThreshold = 10;
	protected static boolean doMitosisCheck = true;
	protected static double mitoSensitivity = 50;
	
	public static Boolean addDialog() {
		
		NonBlockingGenericDialog gd = new NonBlockingGenericDialog("Quick mitosis detector");
		
		// control channel layout
		gd.addSlider("hGEM channel", 1, 5, 1, 1);	
		gd.addSlider("hCdt1 channel", 1, 5, 2, 1);
		gd.addSlider("H2B(nuclei) channel:", 1, 5, 3, 1);
		// getting LoG spot detection parameters
		gd.addSlider("Tracking channel:", 1, 5, targetChannel, 1);
		gd.addNumericField("Diameter for cell detection:", spotRadius*2, 1, 3, "microns");
		gd.addNumericField("Spot detection quality threshold", qualityThreshold, 1, 3, "");
		gd.addCheckbox("Do mitosis detection", doMitosisCheck);
		gd.addSlider("Detection sensitivity", 0, 100, mitoSensitivity);	
		JButton refresh = new JButton("Refresh source");
		refresh.addActionListener (new ActionListener()  
	        {  
	            public void actionPerformed( ActionEvent e )  
	            {  
	            	impOri = WindowManager.getCurrentImage();
	                gd.repaint();
	                //if (impOri == null) IJ.log("No image is open!");
	            }  
	        }); 
		Panel customPane = new Panel();
		customPane.add(refresh);
		gd.addPanel(customPane);
	
		String html = "<html>"
				 +"<h2>Fucci cell fluorescent analysis (ImageJ plugin)</h2>"
				 +" version: 1.1.6<br>"
				 +" date: 2019.03.28<br>"
				 +" author: Ziqiang Huang (Ziqiang.Huang@cruk.cam.ac.uk)<br><br>"
				 +"<h3>Usage:</h3>"
				 +"<&nbsp>Quick detect cells and mitosis for further processing<br>"
				 +" Works with Fucci cell lines developed in Fanni Gergely's lab at CRUK-CI.<br>"
				 +" <&nbsp>(contact: Daphne Huberts / Sarah Carden)<br>"
				 +"<br><&nbsp>This script makes use of Trackmate Spot detection functions.<br>"
				 +" It works on the current active tiff image stack."
				 +" <&nbsp>User will need to specify the spot detection parameters:<br>"
				 +" target channel, average cell radius and so on.<br>"
				 +" <&nbsp>The plugin will use these parameters to detect spots<br>"
				 +" without tracking them. Therefore it will be much faster comparing.<br>"
				 +" to tracking workflow.<br>"
				 +" Additionaly it support a fluorescent intensity based quick mitosis detection.<br>"
				 +" The sensitivity of this detection can be modified by User.<br>"
				 +" Detected Cells and Mitosis will be stored as circle ROI in RoiManager.<br>";
		
		gd.addHelp(html);
		gd.showDialog();
		if (gd.wasCanceled())	return false;
		
		hGEMChannel = (int) gd.getNextNumber();
		hCdt1Channel = (int) gd.getNextNumber();
		dapiChannel = (int) gd.getNextNumber();
		
		targetChannel = (int) gd.getNextNumber();
		spotRadius = gd.getNextNumber()/2;
		qualityThreshold = gd.getNextNumber();
		doMitosisCheck = gd.getNextBoolean();
		mitoSensitivity = gd.getNextNumber();
		
		return true;
	}
	
	
	@Override
	public void run(String arg) {

		if (!addDialog()) return;
		
		// get active image
		if (impOri == null)	impOri = WindowManager.getCurrentImage();
		if (impOri == null) {
			impOri = IJ.openImage();
		}
		impOri.show();
		if (impOri.getNChannels()<2 || impOri.getNChannels()<targetChannel || dapiChannel == hGEMChannel) {
			IJ.error("Input image has to be a multi-channel image stack with DAPI signal in one separate channel!");
			return;
		}
		
		// timing the start
		Date now = new Date();
		SimpleDateFormat dateAndTime = new SimpleDateFormat("E, yyyy.MM.dd - H:mm:ss.SSS");
		double start = System.currentTimeMillis();
		IJ.log("\nQuick mitosis detection execution at: " + dateAndTime.format(now));

		//String imagePath = IJ.getDirectory("image");
		impOri.deleteRoi();
		ImagePlus imp = new Duplicator().run(impOri, 1, impOri.getNChannels(), 1, impOri.getNSlices(), 1, impOri.getNFrames());
		double pixelSize = impOri.getCalibration().pixelWidth;
		
		// print image information to imagej log window
		GetImageInfo.getInfo(impOri);
		// print detection parameters to imagej log window
		if (doMitosisCheck) {
			IJ.log("      Mitosis detection with: Diameter: " + String.valueOf(spotRadius*2) + " µm"
							+ ",  Threshold: " +  String.valueOf(qualityThreshold) 
							+ ",  Sensitivity: " +  String.valueOf(mitoSensitivity) );
		} else {
			IJ.log("      Cell detection with: Diameter: " + String.valueOf(spotRadius*2) + " µm");
		}
		// get image dimension and swap Z and T if T=1
		int[] dims = imp.getDimensions();	// default order: XYCZT
		if (dims[4] == 1) {
		    imp.setDimensions(dims[2],dims[4],dims[3]);	// set dimension to CTZ
		}
		
				
		// Setup settings for TrackMate
		Settings settings = new Settings();
		settings.setFrom(imp);
		
		// Spot analyzer: we want the multi-C intensity analyzer.
		//settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
		//settings.addSpotAnalyzerFactory(new SpotRadiusEstimatorFactory());
		// Spot detector
		settings.detectorFactory = new LogDetectorFactory();
		settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
		Map<String, Object> map = settings.detectorFactory.getDefaultSettings();
		map.put("TARGET_CHANNEL", targetChannel);
		map.put("RADIUS", spotRadius);
		map.put("THRESHOLD", qualityThreshold);	//dialog
		map.put("DO_MEDIAN_FILTERING", false);
		map.put("DO_SUBPIXEL_LOCALIZATION", true);
		settings.detectorSettings = map;
		
		// Run TrackMate and store data into Model
		Model model = new Model();
		TrackMate trackmate = new TrackMate(model, settings);

		boolean ok = trackmate.checkInput();
		if (ok == false) {
			System.out.println(trackmate.getErrorMessage());
		}
		// Find spots
		ok = trackmate.execDetection();
		if (ok == false) {
			System.out.println(trackmate.getErrorMessage());
		}
		// Compute spot features
		ok = trackmate.computeSpotFeatures(true);
		if (ok == false) {
			System.out.println(trackmate.getErrorMessage());
		}
		// Filter spots
		ok = trackmate.execSpotFiltering(true);
		if (ok == false) {
			System.out.println(trackmate.getErrorMessage());
		}

		IJ.log("      Detection completed.");
		// Return spot collection
		SpotCollection dapiSpots = model.getSpots();
		int spotNumber = dapiSpots.getNSpots(true);
		if (!doMitosisCheck)
			IJ.log("      Found " + String.valueOf(spotNumber) + " cells in Channel " + String.valueOf(targetChannel));
		//dapiSpots = model.getSpots();
		Iterator<Spot> spotIterator = dapiSpots.iterator(true);

		int id =1;
		int nMitosis = 0;
		//String mitosisId = "";
		ArrayList<Roi> mitosisRoi = new ArrayList<Roi>();
		int nZeros = String.valueOf(spotNumber).length();
		String rmName;

		RoiManager rm = RoiManager.getInstance();
		if (rm == null) rm = new RoiManager();
		else rm.reset();
		rm.setVisible(false);

		double[] thresholds = MitosisUtility.getMitosisDetectionThreshold(imp, hGEMChannel, dapiChannel, mitoSensitivity);
		
		while (spotIterator.hasNext()) {
			Spot spot = spotIterator.next();
			// get spot position in pixel coordinates
			double x = spot.getFeature("POSITION_X");
			double y = spot.getFeature("POSITION_Y");
			double t = spot.getFeature("FRAME") + 1;
			//d = spot.getFeature('ESTIMATED_DIAMETER');
			double d = spotRadius*2;
			// if the spot is in the object ROI count
			// display spot as roi
			// generate circle selection and add to RoiManager
			
			Roi r = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
			r.setPosition(targetChannel, 1, (int)t);
			rmName = String.format ("%0"+nZeros+"d", id);
			
			if (doMitosisCheck) {
				if (MitosisUtility.mitosisCheckQuick(imp, r, hGEMChannel, dapiChannel, thresholds)) {
					rmName+="-mitosis";
					r.setName(rmName);
					//mitosisId += "\n" + "                            " + String.valueOf(id);
					++nMitosis;
					mitosisRoi.add(r);
				}
			}
			
			rm.add(imp, r, id);	// add roi with imp to RoiManager, SpotID as 3rd argument
			rm.rename(rm.getCount()-1, rmName);
			id++;
			IJ.run(imp, "Select None", "");
			
		}

		if (doMitosisCheck) {
			for (Roi r : mitosisRoi) {
				rm.add(imp, r, rm.getCount());
				rm.rename(rm.getCount()-1, r.getName());
			}
			IJ.log("      Found " 
					+ String.valueOf(nMitosis) + " Mitosis among " 
					+ String.valueOf(spotNumber) + " Cells in Channel " 
					+ String.valueOf(targetChannel));
			IJ.log("      Mitosis ROIs DUPLICATED and Stored at the end of RoiManager.");
		}

		imp.close();
		rm.setVisible(true);
		System.gc();
		double end = System.currentTimeMillis();
		double duration = (end-start)/1000;
		IJ.log("Quick mitosis detection finished after " + String.valueOf(duration) + " second.\n");
		
	}

	public static void main(String[] args) {
		
		String [] ij_args = 
			{ "-Dplugins.dir=C:/Fiji.app/plugins",
			"-Dmacros.dir=C:/Fiji.app/macros" };

		ij.ImageJ.main(ij_args);
		
		DefaultPrefService prefs = new DefaultPrefService();

		targetChannel = prefs.getInt(Integer.class, "persistedDouble", targetChannel);
		spotRadius = prefs.getDouble(Double.class, "persistedDouble", spotRadius);
		qualityThreshold = prefs.getDouble(Double.class, "persistedDouble", qualityThreshold);
		doMitosisCheck = prefs.getBoolean(Boolean.class, "persistedBoolean", doMitosisCheck);
		mitoSensitivity = prefs.getDouble(Double.class, "persistedDouble", mitoSensitivity);
		
		MitosisDetector md = new MitosisDetector();
		md.run(null);
		
		prefs.put(Integer.class, "persistedDouble", targetChannel);
		prefs.put(Double.class, "persistedDouble", spotRadius);
		prefs.put(Double.class, "persistedDouble", qualityThreshold);
		prefs.put(Boolean.class, "persistedBoolean", doMitosisCheck);
		prefs.put(Double.class, "persistedDouble", mitoSensitivity);
	
	}
}
