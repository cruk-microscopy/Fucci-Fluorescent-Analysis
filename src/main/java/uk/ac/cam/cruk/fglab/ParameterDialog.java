package uk.ac.cam.cruk.fglab;

import fiji.util.gui.GenericDialogPlus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.FolderOpener;
import ij.plugin.PlugIn;

//import java.awt.Color;
import java.awt.Font;
import java.io.File;

import org.scijava.prefs.DefaultPrefService;
//import org.scijava.prefs.PrefService;

// !!! persistence in testing mode, not fully configured
// Known problem: after restart Fiji, previous parameters are lost
public class ParameterDialog implements PlugIn {
	
	// input parameters with initialised default value
	//protected static int activeImgCount = WindowManager.getImageCount();
	//protected static int activeImgCount;
	
	protected static boolean getActiveImage = false;
	protected static int activeImgNum = -1;
	protected static ImagePlus activeImg;
	protected static String imgPath;
	
	protected static int targetChannel = 3;
	protected static double spotRadius = 10;
	protected static boolean extraParam = false;
	
	protected static double qualityThreshold = 10.0;
	protected static int frameGap = 3;
	protected static double linkingMax = 20;
	protected static double closingMax = 20;
	
	protected static boolean doWeka = false;
	protected static String modelPath;
	protected static double minLength = 240; // minutes
	protected static double mitoSensitivity = 50;
	
	protected static boolean saveTable = false;
	protected static boolean saveROI = false;
	protected static boolean saveMitosisTrackStack = false;
	protected static String saveDir = "or save at image location";
	
	protected static boolean saveWithImg = false;
	
	public static String[] activeImageList() {
		int numOpenImg = WindowManager.getImageCount();
		String[] titles = new String[numOpenImg+1];
		titles[0] = "open image on disk";
		System.arraycopy(WindowManager.getImageTitles(), 0, titles, 1, numOpenImg);
		return titles;
	}
	
	@Override
	public void run(String arg0) {
		mainDialog();
		ImagePlus img = null;
		if (getActiveImage) {
			int activeImageID = WindowManager.getNthImageID(activeImgNum);
			img = WindowManager.getImage(activeImageID);
		} else {
			img = IJ.openImage(imgPath);	
		}
		if (img == null) {
			IJ.log("Input image wrong!");
			return;
		}
	}
	
	public static void main() {
		
		if (IJ.versionLessThan("1.52f")) System.exit(0);
		
		// make use of scijava parameter persistence storage	
		DefaultPrefService prefs = new DefaultPrefService();
		imgPath = prefs.get(String.class, "persistedString", imgPath);
		targetChannel = prefs.getInt(Integer.class, "persistedDouble", targetChannel);
		spotRadius = prefs.getDouble(Double.class, "persistedDouble", spotRadius);
		qualityThreshold = prefs.getDouble(Double.class, "persistedDouble", qualityThreshold);
		
		linkingMax = prefs.getDouble(Double.class, "persistedDouble", linkingMax);
		closingMax = prefs.getDouble(Double.class, "persistedDouble", closingMax);
		frameGap = prefs.getInt(Integer.class, "persistedDouble", frameGap);
		
		doWeka = prefs.getBoolean(Boolean.class, "persistedBoolean", doWeka);
		modelPath = prefs.get(String.class, "persistedString", modelPath);
		
		mitoSensitivity = prefs.getDouble(Double.class, "persistedDouble", mitoSensitivity);
		minLength = prefs.getDouble(Double.class, "persistedDouble", minLength);
		
		saveTable = prefs.getBoolean(Boolean.class, "persistedBoolean", saveTable);
		saveROI = prefs.getBoolean(Boolean.class, "persistedBoolean", saveROI);
		saveMitosisTrackStack = prefs.getBoolean(Boolean.class, "persistedBoolean", saveMitosisTrackStack);
		saveDir = prefs.get(String.class, "persistedString", saveDir);
		
		
		ParameterDialog pd = new ParameterDialog();
		pd.run(null);
	}
	
	public static boolean trackMateDialog() {
		
		
		return true;
	}
	
	public static boolean mainDialog() {
		
		final Font highlightFont = new Font("Helvetica", Font.BOLD, 12);
		//final Color highlightColor = Color.BLUE;
		
		GenericDialogPlus gd = new GenericDialogPlus("FUCCI fluorescent analysis");
		//	file open option group
		String fileOpenMessage = "File open options:";
		gd.setInsets(10,0,0);
		gd.addMessage(fileOpenMessage, highlightFont);
		String [] imgTitles = activeImageList();
		gd.setInsets(0,0,0);
		gd.addChoice("Get active image", imgTitles, imgTitles[0]);
		gd.addDirectoryOrFileField("Open image on disk", imgPath);
		
		//	trackmate option group
		String processMessage = "Cell tracking options:";
		gd.setInsets(10,0,0);
		gd.addMessage(processMessage, highlightFont);
		//gd.setInsets(0,159,0);
		gd.addNumericField("Tracking channel:", targetChannel, 0);
		gd.addNumericField("Diameter for cell detection:", spotRadius*2, 1, 3, "microns");
		gd.addNumericField("Spot detection quality threshold", qualityThreshold, 1, 3, "");
		
		gd.addNumericField("Linking max distance", linkingMax, 0, 3, "microns");
		gd.addNumericField("Gap closing max distance", closingMax, 0, 3, "microns");
		gd.addNumericField("Max frame gap", frameGap, 0, 3, "frames");
		
		gd.addCheckbox("Use trained classifier for mitosis detection?", doWeka);
		gd.addFileField("Mitosis detector model file", modelPath);
		gd.addSlider("Detection sensitivity", 0, 100, mitoSensitivity);	
		gd.addNumericField("Minimum track length", minLength, 0, 3, "minutes");
		
		// result saving option group
		String saveMessage = "Result saving options:";
		gd.setInsets(10,0,0);
		gd.addMessage(saveMessage, highlightFont);
		int row = 1, column = 3;
		String[] labels = {"result data table","Spot as ROI","mitosis track"};
		boolean[] states = {saveTable,saveROI,saveMitosisTrackStack};
		gd.setInsets(0,119,0);
		gd.addCheckboxGroup(row, column, labels, states);
		gd.addDirectoryOrFileField("Choose different folder to save results", saveDir);
		String html = "<html>"
				 +"<h2>Fucci cell fluorescent analysis (ImageJ plugin)</h2>"
				 +" version: 1.0.4<br>"
				 +" date: 2019.01.24<br>"
				 +" author: Ziqiang Huang (Ziqiang.Huang@cruk.cam.ac.uk)<br><br>"
				 +"<h3>Usage:</h3>"
				 +"<&nbsp>Automate cell tracking and fluorescent analysis <br>"
				 +" with Fucci cell lines developed in Fanni Gergely's lab at CRUK-CI.<br>"
				 +" <&nbsp>(contact: Daphne Huberts / Sarah Carden)<br>"
				 +"<br><&nbsp>This script makes use of Trackmate.<br>"
				 +" It take an active tiff image stack as input."
				 +" <&nbsp>User will need to specify the tracking parameters:<br>"
				 +" target channel, average cell radius and so on.<br>"
				 +" <&nbsp>The plugin will use these parameters to build a trackmate model<br>"
				 +" and run it on the given image stack. The result will be displayed.<br>"
				 +" as a ResultTable named \"Fucci cell tracking data table\",<br>"
				 +"  Detected track-spots will be stored as circle ROI in RoiManager.<br>"
				 +"<br><&nbsp>Known issue: diameter map does not return system memory (4 times the input file).<br>";
		gd.addHelp(html);
		gd.showDialog();
		
		activeImgNum = gd.getNextChoiceIndex();
		activeImg = WindowManager.getImage(WindowManager.getNthImageID(activeImgNum));
		if (activeImgNum!=0)	getActiveImage = true;
		else	getActiveImage = false;
		
		imgPath = gd.getNextString();
		
		
		targetChannel = (int) gd.getNextNumber();
		spotRadius = gd.getNextNumber()/2; // get radius from diameter
		qualityThreshold = gd.getNextNumber();
		
		linkingMax = gd.getNextNumber();
		closingMax = gd.getNextNumber();
		frameGap = (int) gd.getNextNumber();
		
		doWeka = gd.getNextBoolean();
		modelPath = gd.getNextString();
		mitoSensitivity = gd.getNextNumber();
		
		minLength = gd.getNextNumber();
		
		saveTable = gd.getNextBoolean();
		saveROI = gd.getNextBoolean();
		saveMitosisTrackStack = gd.getNextBoolean();
		
		saveDir = gd.getNextString();
		
		if (saveDir.equals("or save at image location")) {
			saveWithImg = true;
		}
		
		if (gd.wasCanceled())	return false;
		
		File imgF = new File(imgPath);
		
		return true;
	}
}