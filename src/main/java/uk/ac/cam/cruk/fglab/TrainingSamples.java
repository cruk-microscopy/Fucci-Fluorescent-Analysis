package uk.ac.cam.cruk.fglab;

import java.awt.Button;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

import org.scijava.prefs.DefaultPrefService;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.blob.ManyBlobs;
import ij.gui.GenericDialog;
import ij.gui.HistogramWindow;
import ij.gui.ImageWindow;
import ij.gui.NewImage;
import ij.gui.Overlay;
import ij.gui.PlotWindow;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.plugin.ChannelSplitter;
import ij.plugin.MontageMaker;
import ij.plugin.RGBStackMerge;
import ij.plugin.RoiEnlarger;
import ij.plugin.RoiScaler;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;

public class TrainingSamples {
	
	/* A class to store training samples images as stacks.
	 * It requires a class name, and if avialable a class alias;
	 * It also need a source image to fetch training samples.
	 * 
	 * Class names are stored as title of the image (plus) of each training sample
	 * in global variable ArrayList<ImagePlus> trainingSamples 
	 */
	
	protected ImagePlus sourceImage;
	
	protected ArrayList<ImagePlus> trainingSamples = new ArrayList<ImagePlus>();
	
	//protected ArrayList<String> classNames = new ArrayList<String>();
	//protected ArrayList<String> classAlias = new ArrayList<String>();
	
	protected int imageSize;	//width and height of image window in pixel
	
	protected Boolean calibrated;
	//protected Double scaleFactorX;
	//protected Double scaleFactorY;
	
	protected final String[] methods = 
		{"Triangle","Otsu","Li","IsoData","Default","Huang","Intermodes","IJ_IsoData","MaxEntropy",
			"Mean","Yen","MinError","Minimum","Moments","Percentile","RenyiEntropy","Shanbhag"};
	protected String thresholdMethod = methods[0];
	protected final Color[] Colors = 
		{Color.YELLOW, Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA, Color.CYAN, 
				Color.ORANGE, Color.PINK, Color.GRAY, Color.WHITE, Color.BLACK};
	protected final String[] colorStrings =
		{"YELLOW","RED","GREEN","BLUE","MAGENTA","CYAN","ORANGE","PINK","GRAY","WHITE","BLACK"};
	protected int overlayButtonAction=0;

	protected Boolean drawRoiAll = true;
	protected Boolean drawRoiCenter = true;
	protected Boolean drawRoiEdge = true;
	protected int colorAllIdx = 0;
	protected int colorCenterIdx = 1;
	protected int colorEdgeIdx = 2;
	protected Color colorRoiAll = Colors[colorAllIdx];
	protected Color colorRoiCenter = Colors[colorCenterIdx];
	protected Color colorRoiEdge = Colors[colorEdgeIdx];
	
	protected double edgeSizeInMicron = 1.5; // micron
	//protected double edgeSizeInPixel = 20.0;
	
	protected Overlay roiAll;
	protected Overlay roiCenter;
	protected Overlay roiEdge;
	
	protected boolean changeAllEdgeSize = false;
	
	protected final static int montageBorderWidth = 5;	// 5 pixel for gallery border
	/* Class constructor:
	 * TrainingSamples();
	 * TrainingSamples(ImagePlus);
	 */
	public TrainingSamples () {	// constructor for no input imageplus
		if (WindowManager.getImageCount()==0) {
			IJ.log("No image is open. Can not create training samples.");
			this.sourceImage = null;
			//return;
		} else {
			this.sourceImage = WindowManager.getCurrentImage();
		}
		this.imageSize = 256;
		this.calibrated = false;
		this.drawRoiAll = true; this.drawRoiCenter = true; this.drawRoiEdge = true;
		this.colorRoiAll = Color.YELLOW; this.colorRoiCenter = Color.RED; this.colorRoiEdge = Color.GREEN;
		//this.edgeSizeInPixel = 20.0;	//pixel
	}
	public TrainingSamples (ImagePlus imp) {	// constructor for no input imageplus
		this.sourceImage = imp;
		this.imageSize = 256;	// image size not neccessarily be the source image size (width/height)
		this.calibrated = false;
		this.drawRoiAll = true; this.drawRoiCenter = true; this.drawRoiEdge = true;
		this.colorRoiAll = Color.YELLOW; this.colorRoiCenter = Color.RED; this.colorRoiEdge = Color.GREEN;
		//this.edgeSizeInPixel = 20.0;	//pixel
	}
	
/* 
 * Functions to set and get class parameters:
 */
	// function to set new source image
	public void setSource(ImagePlus imp) {
		if (imp==null) return;
		ImageWindow window = imp.getWindow();
		if (window instanceof PlotWindow || window instanceof HistogramWindow) return;
		this.sourceImage = imp;
	}
	public ImagePlus getCurrentSource() {
		return this.sourceImage;
	}
	// function to automatically fetch new source image with current open images in Fiji
	public ImagePlus getNewSource() {
		int nImage = WindowManager.getImageCount();
		if (nImage==0) return null;
		ImagePlus currentImage = IJ.getImage();
		if (checkSource(currentImage))	return currentImage;
		int[] IDList = WindowManager.getIDList();
		for (int ID : IDList) {
			if (checkSource(WindowManager.getImage(ID)))
				return WindowManager.getImage(ID);
		}
		return null;
	}
	// function to check if a image is suitable to be the source image:
	// it can not be histogram or plot (future: it can not be montage)
	public boolean checkSource(ImagePlus imp) {
		if (imp==null)	return false;
		ImageWindow window = imp.getWindow();
		if (window instanceof PlotWindow || window instanceof HistogramWindow) return false;
		//if (imp.getTitle().toLowerCase().contains("gallery")) return false;
		return true;
	}
	// function to correct class label from "ROI:" to "Cell:"
	// as of 2019.09.16 update
	public void correctSourceClassLabel() {
		if (this.sourceImage==null) return;
		ImageStack stack = this.sourceImage.getStack();
		String[] sliceLabels = stack.getSliceLabels();
		
		for (int i=0; i<sliceLabels.length; i++) {
			if (sliceLabels[i]==null) continue;
			sliceLabels[i] = sliceLabels[i].replace("ROI:", "Cell:");
			if (!sliceLabels[i].contains(", Cell:")) sliceLabels[i] = sliceLabels[i] + ", Cell:";
			//System.out.println("Cell(ROI) Label does not found in slice " + i + "in source image: " + this.sourceImage.getTitle());
		}
	}
	
	/*
	 * Source status: 
	 * 			0:	not exist; 
	 * 			1:	image, without overlay; 
	 * 			2:	image, with overlay
	 * 			3:	stack without overlay;
	 * 			4:	stack with overlay
	 */
	public int checkSource() {
		if (this.sourceImage==null) {
			IJ.log("source image not set to fetch training samples!");
			return 0;
		}
		if (!this.sourceImage.isStack() || this.sourceImage.getNSlices()<=1) {
			if (this.sourceImage.getOverlay()==null)	return 1;
			else	return 2;
		} else {
			if (this.sourceImage.getOverlay()==null)	return 3;
			else	return 4;
		}
	}
	public void setImageSize(int size) {
		this.imageSize = size;
	}
	public void useCalibratedUnit(Boolean usePhysicalUnit) {
		this.calibrated = usePhysicalUnit;
	}
	public void setEdgeSizeMicron(Double edgeSize) {	//edgeSize in pixel
		this.edgeSizeInMicron = edgeSize;
	}
	public void drawRoi(String roiLocation, Boolean draw) {
		switch(roiLocation.toLowerCase()) {
		case "all":
			this.drawRoiAll = draw;
			break;
		case "center":
			this.drawRoiCenter = draw;
			break;
		case "edge":
			this.drawRoiEdge = draw;
			break;		
		}
	}
	public void setRoiColor(String roiLocation, Color roiColor) {
		switch(roiLocation.toLowerCase()) {
		case "all":
			this.colorRoiAll = roiColor;
			break;
		case "center":
			this.colorRoiCenter = roiColor;
			break;
		case "edge":
			this.colorRoiEdge = roiColor;
			break;		
		}
	}
	
	/*
	 * Functions to configure the training samples and their layouts
	 */
	
	
	public ImagePlus addNewEmptyClass(String newClassName) {
		/*
		 * Function to create an empty image window with new class name
		 * and returns the imageplus instance
		 */
		int sourceStatus = checkSource();
		if (sourceStatus==0)	return null;
		if (classExist(newClassName)) {
			IJ.error("Class " + newClassName + " already exist!");
			return null;
		}
		//ImagePlus newClass = NewImage.createByteImage(newClassName, this.imageSize, this.imageSize, 1, NewImage.FILL_BLACK);
		//ImagePlus newClass = NewImage.createImage(newClassName, this.imageSize, this.imageSize, 1, this.sourceImage.getBitDepth(), NewImage.FILL_BLACK);
		//ImagePlus newClass = NewImage.createImage(newClassName, this.sourceImage.getWidth(), this.sourceImage.getHeight(), 1, this.sourceImage.getBitDepth(), NewImage.FILL_BLACK);
		ImagePlus newClass = NewImage.createImage(newClassName, this.sourceImage.getWidth(), this.sourceImage.getHeight(), 1, this.sourceImage.getBitDepth(), NewImage.FILL_BLACK);
		if (this.sourceImage.isHyperStack()) {
			newClass = IJ.createHyperStack(newClassName, 
					this.sourceImage.getWidth(), this.sourceImage.getHeight(), 
					this.sourceImage.getNChannels(), 1, this.sourceImage.getNFrames(), 
					this.sourceImage.getBitDepth());
		}
		newClass.setLut(this.sourceImage.getProcessor().getLut());
		newClass.show();
		newClass.getWindow().setVisible(false); //show image, hide window for configuration
		this.trainingSamples.add(newClass);
		updateTrainingSampleWindow();
		newClass.setDisplayRange(this.sourceImage.getDisplayRangeMin(), this.sourceImage.getDisplayRangeMax());
		newClass.getWindow().setVisible(true);
		
		return newClass;
	}
	public void addNewClass(
			String newClassName, 
			String newClassAlias	// alias will be used to identify class sample from source stack using slice label
			//String aliasExclude
			) {
		// check source and class exist (done with addNewEmptyClass)
		ImagePlus newClass = addNewEmptyClass(newClassName);
		if (newClass==null)	return;
		
		newClass.getWindow().setVisible(false);	// hide new class window for fast operation
		// prompt for user to decide if keep or remove slices in the source image stack
		String title = "Keep slice in source?";
		String body = "Press OK to keep the slice in source" + 
					"\nOtherwise, press Cancel to delete the slice after added into new class.";
		//boolean keepSliceInSource = IJ.showMessageWithCancel(title, body);
		boolean keepSliceInSource = true; // 2020.02.03 change: always keep slice in source
		
		// parse the class label, locate slices containining the class label as "ROI:sl" or "ROI:i-5" and so on
		ArrayList<Integer> addIndices = new ArrayList<Integer>();
		if (newClassAlias!=null && newClassAlias.length()>0 && checkSource()>2) { // no class label or 2D source, add the current slice from source to new class
			int nCharAlias = newClassAlias.length();	
			ImageStack stack = this.sourceImage.getStack();
			String[] labels = stack.getSliceLabels();
			
			for (int i=0; i<labels.length; i++) {
				if (labels[i]==null || labels[i].length()==0) continue;
				int classLabelIdx = labels[i].indexOf("Cell:");
				if (classLabelIdx == -1 || labels[i].length()<(classLabelIdx+5+nCharAlias)) continue;	// class label does not exist or too short in slice label
				String classLabel = labels[i].substring(classLabelIdx+5, classLabelIdx+5+nCharAlias);
				if (classLabel.equals(newClassAlias)) {
					int[]stackPosition = this.sourceImage.convertIndexToPosition(i+1);
					if (!addIndices.contains(stackPosition[1]))
						addIndices.add(stackPosition[1]);
				}
			}
			if (!keepSliceInSource)	Collections.reverse(addIndices);	// important: in the case of delete slice from source, the slice order need to be decremented
		}
		// add located slices to new class window
		if (addIndices.size()==0) { // add only current slice in source to new class
			if (!HyperStackUtility.replaceSlice(this.sourceImage, newClass, 4, this.sourceImage.getZ(), 1, keepSliceInSource))
				System.out.println("Can not add slice " + this.sourceImage.getZ() + " from source image: " + this.sourceImage.getTitle());
			//if (!replaceSlice(this.sourceImage, newClass, this.sourceImage.getZ(), 1, keepSliceInSource))
			//	System.out.println("Can not add slice " + this.sourceImage.getZ() + " from source image: " + this.sourceImage.getTitle());
		} else {
			HyperStackUtility.replaceSlice(this.sourceImage, newClass, 4, addIndices.get(0), 1, keepSliceInSource);
			//setSource(this.sourceImage);
			if (addIndices.size()==1)	return;
			addIndices.remove(0);
			for (Integer addIdx : addIndices) {
				if (!HyperStackUtility.addSlice(this.sourceImage, newClass, 4, addIdx, 0, keepSliceInSource))
					System.out.println("Could not add slice " + addIdx + " from source image: " + this.sourceImage.getTitle());
			}
		}
		// update all class window, wrap up, return
		updateTrainingSampleWindow();
		newClass.getWindow().setVisible(true);
		return;
	}
	// function to add all specific slices from a image stack into class 
	public void addImageToClass (
			ImagePlus imp,
			ArrayList<Integer> sliceIdx,
			String className,
			boolean keepSliceInSource
			) {
		Collections.sort(sliceIdx); 
		if (!keepSliceInSource) Collections.reverse(sliceIdx);	// sort slice index from high to low, if delete in source
		ArrayList<String> classNames = this.getClassNames();
		ImagePlus classImage = null;
		if (classNames==null || classNames.size()==0 || !classNames.contains(className)) {	// class not exist, create new class to store images
			classImage = addNewEmptyClass(className);
			if (!HyperStackUtility.replaceSlice(imp, classImage, 4, sliceIdx.get(0), 1, keepSliceInSource))
				System.out.println("Can not add slice " + sliceIdx.get(0) + " from source image: " + imp);
		} else {	// class exist, add images to the specific class
			classImage = getClassImagePlus(className);
			if (!HyperStackUtility.addSlice(imp, classImage, 4, sliceIdx.get(0), 0, keepSliceInSource))
				System.out.println("Could not add slice " + sliceIdx.get(0) + " from source image: " + imp);
		}
		if (classImage==null) return;
		sliceIdx.remove(0);
		for (int idx : sliceIdx) {
			if (!HyperStackUtility.addSlice(imp, classImage, 4, idx, 0, keepSliceInSource))
				System.out.println("Could not add slice " + idx + " from source image: " + imp);
		}	
	}
	// function to get the imageplus from a certain class specified by name
	public ImagePlus getClassImagePlus( String className ) {
		ImagePlus classImage = null;
		for (ImagePlus trainingSample : this.trainingSamples) {
			if (trainingSample.getTitle().equals(className))
				classImage = trainingSample;
		}
		return classImage;
	}
	// function to get the imageplus from a certain class specified by index
	public ImagePlus getClassImagePlus( int classIndex ) {
		if (classIndex<0 || classIndex>this.trainingSamples.size()-1) return null;
		return this.trainingSamples.get(classIndex);
	}
	// function to remove a class with its associated imageplus from training samples
	public void removeClass( String className ) {
		if (!classExist(className)) return;
		ImagePlus delete = null;
		for (ImagePlus trainingSample : this.trainingSamples) {
			if (trainingSample.getTitle().equals(className))
				delete = trainingSample;
		}
		if (delete == null)	return;
		trainingSamples.remove(delete);
		delete.changes = false; delete.close(); System.gc();
		for (ImagePlus trainingSample : this.trainingSamples) {
			ImageWindow win = trainingSample.getWindow();
			deleteMoveClassButton(win, className);
		}
		updateTrainingSampleWindow();
	}
	// function to remove a class with its associated imageplus from training samples
	public void renameClass( String oldClassName, String newClassName ) {
		if (!classExist(oldClassName)) return;
		if (classExist(newClassName)) {
			IJ.error("Class with name " + newClassName + " already exist!");
			return;
		}
		for (ImagePlus trainingSample : this.trainingSamples) {
			if (trainingSample.getTitle().equals(oldClassName)) {
				int idx = this.trainingSamples.indexOf(trainingSample);
				trainingSample.setTitle(newClassName);
				trainingSample.changes = true;
				this.trainingSamples.set(idx, trainingSample);
			}
		}
		for (ImagePlus trainingSample : this.trainingSamples) {
			ImageWindow win = trainingSample.getWindow();
			renameMoveClassButton(win, oldClassName, newClassName);
			//updateClassSetupPanel(win, classNames);
		}
		updateTrainingSampleWindow();
	}
	// function to apply class label to source image
	public void applyClassLabelToSource ( String className, String classLabel ) {
		// fetch the current source image and class images
		if (this.sourceImage==null || this.trainingSamples.size()==0) return;
		// get the image stack and slice labels(clone) of the class specified by class name
		ImagePlus classImage = this.getClassImagePlus(className);
		if (classImage==null) return;
		// get image stack from source, labels from source and class image(clone)
		int nSlices = this.sourceImage.getNSlices();
		ImageStack sourceStack = this.sourceImage.getStack();
		String[] sourcelabels = sourceStack.getSliceLabels();	
		String[] sliceLabels = classImage.getStack().getSliceLabels().clone();
		for (int i=0; i<sliceLabels.length; i++) {
			if (sliceLabels[i]==null) continue;
			int idx = sliceLabels[i].indexOf(", Slice:");
			if (idx==-1) continue;
			String partString = sliceLabels[i].substring(idx+8, sliceLabels[i].length());
			String[] parts = partString.split("\\D");
			if (parts.length==0) continue;
			int sourceSliceIndex = Integer.valueOf(parts[0]);
			if (sourceSliceIndex<1 || sourceSliceIndex>nSlices)	continue;
			String oldLabel = sourcelabels[sourceSliceIndex-1];
			int idx2 = oldLabel.indexOf("Cell:");
			if (idx2==-1) continue;
			String newLabel = oldLabel.substring(0, idx2) + "Cell:" + classLabel + "," + oldLabel.substring(idx2+5, oldLabel.length());
			sourcelabels[sourceSliceIndex-1] = newLabel;
			//this.sourceImage.getStack().setSliceLabel(newLabel, sourceSliceIndex);
		}
		ArrayList<String> labelList = new ArrayList<String>(Arrays.asList(sliceLabels));
		
		// iterate through source labels to find matching labels
		for (int i=0; i<sourcelabels.length; i++) {
			if (sourcelabels[i]==null) continue;	// skip null entries
			if (labelList.contains(sourcelabels[i])) {	// found matching
				int labelIndex = sourcelabels[i].indexOf("Cell:");	// locate "Cell:" (1st) string
				if (labelIndex==-1) {
					sourcelabels[i] += "Cell:" + classLabel;	// 1st string not found, simply add it in the end
				} else {
					int labelEndIndex = sourcelabels[i].indexOf(",", labelIndex); // 1st string found, locate end of label as "," (2nd) string
					if (labelEndIndex==-1) {	// 2nd string not found, indicating "Cell:" is the last slice label component,
						sourcelabels[i] = sourcelabels[i].substring(0, labelIndex) 
								+ "Cell:" + classLabel 
								+ sourcelabels[i].substring(labelIndex+5, sourcelabels[i].length());
					} else {	// 2nd string found, indicating more label component after "Cell: ....,"
						sourcelabels[i] = sourcelabels[i].substring(0, labelIndex) 
								+ "Cell:" + classLabel 
								+ sourcelabels[i].substring(labelEndIndex, sourcelabels[i].length());
					}
				}
			}
		}
		
		for (int i=0; i<sourceStack.size(); i++) {
			sourceStack.setSliceLabel(sourcelabels[i], i+1);
		}
		this.sourceImage.setStack(sourceStack);
		this.sourceImage.changes = true;
		this.sourceImage.getWindow().updateImage(this.sourceImage);
	}

	
	/*
	 * Function groups to control the GUI for image winodw:
	 *  check if panel exist
	 *  check if button exist
	 *  add panel to window
	 *  add button to panel	
	 */
	
	public static boolean checkButton( ImageWindow window, String buttonName ) {
		Component[] panels = window.getComponents();
		for (Component panel : panels) {
			if (panel instanceof JPanel) {
				Component[] buttons = ((JPanel) panel).getComponents();
				for (Component button : buttons) {
					if (button instanceof Button) {
						if (((Button)button).getLabel().equals(buttonName)) {
							return true;
						} else continue;
					} else continue;
				}
			} else continue;
		}
		return false;
	}
	public static Button getButton( ImageWindow window, String buttonName ) {
		Component[] panels = window.getComponents();
		for (Component panel : panels) {
			if (panel instanceof JPanel) {
				Component[] buttons = ((JPanel) panel).getComponents();
				for (Component button : buttons) {
					if (button instanceof Button) {
						if (((Button)button).getLabel().equals(buttonName)) {
							return (Button)button;
						} else continue;
					} else continue;
				}
			} else continue;
		}
		return null;
	}
	public static boolean checkPanel( ImageWindow window, String panelName ) {
		Component[] panels = window.getComponents();
		for (Component panel : panels) {
			if (panel instanceof JPanel) {
				if (((JPanel)panel).getName().equals(panelName)) {
					return true;
				} else continue;
			} else continue;
		}
		return false;
	}
	public static JPanel getPanel( ImageWindow window, String panelName ) {
		Component[] panels = window.getComponents();
		for (Component panel : panels) {
			if (panel instanceof JPanel) {
				if (((JPanel)panel).getName().equals(panelName)) {
					return (JPanel)panel;
				} else continue;
			} else continue;
		}
		return null;
	}
	

	
	/*
	 * GUI panel and buttons for:
	 * overlay, histogram, montage(gallery), class setup
	 */
	// function to update training sample windows: overlay panel, histogram panel, gallery panel, and class setup panel
	public void updateSourceWindow() {
		if (checkSource()==0) return;
		ImageWindow sourceWindow = getCurrentSource().getWindow();
		//double mag = sourceWindow.getCanvas().getMagnification();
		if (!checkPanel(sourceWindow, "overlayPanel"))
			addOverlayPanel(sourceWindow);
		
		if (!checkPanel(sourceWindow, "galleryPanel"))
			addGalleryPanel(sourceWindow);
		if (!checkPanel(sourceWindow, "delSamplePanel"))
			addDelSamplePanel(sourceWindow);
		sourceWindow.pack();
		//sourceWindow.getCanvas().setMagnification(mag);
		//WindowManager.setCurrentWindow(sourceWindow);	new Zoom().run("scale");
	}
	// function to update training sample windows: overlay panel, histogram panel, gallery panel, and class setup panel
	public void updateTrainingSampleWindow() {
		int nClasses = this.trainingSamples.size();		
		if (nClasses==0) {
			System.out.println("No classes defined!");
			return;
		}
		ArrayList<String> classNames = this.getClassNames();
		for (ImagePlus trainingSample : this.trainingSamples) {
			ImageWindow win = trainingSample.getWindow();
			//double mag = win.getCanvas().getMagnification();
			// also add ROI, gallery and histogram panel
			if (!checkPanel(win, "overlayPanel")) addOverlayPanel(win);
			
			if (!checkPanel(win, "galleryPanel")) addGalleryPanel(win);
			updateClassSetupPanel(win, classNames);
			win.pack();
			//win.getCanvas().setMagnification(mag);
			//WindowManager.setWindow(win);	new Zoom().run("scale");
		}
	}
	// create ROI and Overlay panel, and add to image window at the bottom
	public void addOverlayPanel(ImageWindow window) {
		JPanel buttonPanel = new JPanel();
		buttonPanel.setName("overlayPanel");
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        Button btnAutoROI = addAutoROIButton(window); buttonPanel.add(btnAutoROI);
        Button btnShrinkROI = addShrinkROIButton(window); buttonPanel.add(btnShrinkROI);
        Button btnEnlargeROI = addEnlargeROIButton(window); buttonPanel.add(btnEnlargeROI);
        Button btnShowHideOverlay = addOverlayButton(window); buttonPanel.add(btnShowHideOverlay);
        Button btnSetup = addOverlaySetupButton(window); buttonPanel.add(btnSetup);
        buttonPanel.setBackground(window.getBackground());
        window.add(buttonPanel);
		window.pack();
	}
		// create auto cell ROI button
		public Button addAutoROIButton(ImageWindow window) {
		    Button makeAutoROI = new Button("Auto ROI"); 
		    makeAutoROI.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {makeCellRoi(window);}
		    }); 
		    return makeAutoROI;
		}
		// create auto cell ROI button
		public Button addShrinkROIButton(ImageWindow window) {
		    Button shrinkROI = new Button("-"); 
		    shrinkROI.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {enlargeCellRoi(window, false);}
		    }); //System.out.println(ae.paramString());
		    return shrinkROI;
		}
		// create auto cell ROI button
		public Button addEnlargeROIButton(ImageWindow window) {
		    Button enlargeROI = new Button("+"); 
		    enlargeROI.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {enlargeCellRoi(window, true);}
		    }); 
		    return enlargeROI;
		}
		// create auto cell ROI button
		public Button addOverlayButton(ImageWindow window) {
		    Button addOverlayAction = new Button("Overlay"); 
		    addOverlayAction.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {overlayAction(window);}
		    }); 
		    return addOverlayAction;
		}
		// create auto cell ROI button
		public Button addOverlaySetupButton(ImageWindow window) {
		    Button setupOverlay = new Button("Setup"); 
		    setupOverlay.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {overlaySetup(window);}
		    });
		    return setupOverlay;
		}
	
		
	// create gallery panel, and add to image window at the bottom
	public static void addGalleryPanel(ImageWindow window) {
			JPanel buttonPanel = new JPanel();
			buttonPanel.setName("galleryPanel");
	        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
	        Button btnShowGallery = addGalleryButton(window); buttonPanel.add(btnShowGallery);
	        Button btnCloseGallery = closeAllGalleryButton(window); buttonPanel.add(btnCloseGallery);
	        buttonPanel.setBackground(window.getBackground());
	        window.add(buttonPanel);
			window.pack();
	}
		// create gallery button
		public static Button addGalleryButton(ImageWindow window) {
			Button makeMontage = new Button("Gallery"); 
		    makeMontage.addActionListener(new ActionListener() { 
		    	@Override 
			    public void actionPerformed(ActionEvent ae) {makeGallery(window);} 
			});  
		    return makeMontage;
		}
		// create close all gallery button
		public static Button closeAllGalleryButton(ImageWindow window) {
		    Button closeGallery = new Button("Close All"); 
		    closeGallery.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) {closeAllGallery(window);}  
		    }); 
		    return closeGallery;
		}
	// create delete sample panel in the source image window at the bottom
	public void addDelSamplePanel(ImageWindow window) {
		JPanel buttonPanel = new JPanel();
		buttonPanel.setName("delSamplePanel");
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        Button btnCheckDuplicate = addCheckDuplicateButton(window); buttonPanel.add(btnCheckDuplicate);
        Button btnDeleteSample = addDelButton(window); buttonPanel.add(btnDeleteSample);
        buttonPanel.setBackground(window.getBackground());
        window.add(buttonPanel);
		window.pack();
	}
		// check duplicate images in image stack
		public Button addCheckDuplicateButton(ImageWindow window) {
			 Button checkDuplicate = new Button("Check Duplicate"); 
			 checkDuplicate.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) {
			        	Thread thread = new Thread(new Runnable() {
			    			public void run() {
					        	ImagePlus imp = window.getImagePlus();
					        	HyperStackUtility.checkDuplicate(null, imp, 4, -1);
			        	}});
			        	thread.start();
			        }
			    }); 
			    return checkDuplicate;
		}
	// create class manipulation panel, and add to image window at the bottom
	public void updateClassSetupPanel(ImageWindow window, ArrayList<String> classNames) {
		//ArrayList<String> classNames = this.getClassNames();
		JPanel buttonPanel = getPanel(window, "classSetupPanel");
		if (buttonPanel==null) {	// class setup panel does not exist
			buttonPanel = new JPanel();
			buttonPanel.setName("classSetupPanel");
	        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
	        Button btnAddSample = addAddButton(window);	buttonPanel.add(btnAddSample);
	        Button btnDeleteSample = addDelButton(window); buttonPanel.add(btnDeleteSample);
		}
		for (String className : classNames) {
			if (window.getImagePlus().getTitle().equals(className)) continue;
			if (!checkButton(window, "Move to "+className)) {
				Button btnMoveSample = addMoveClassButton(window, className);
				buttonPanel.add(btnMoveSample);
			}
		}
        buttonPanel.setBackground(window.getBackground());
        window.add(buttonPanel);
		window.pack();
	}
		// create add sample button
		public Button addAddButton(ImageWindow window) {
		    Button addSample = new Button("+ Sample"); 
		    addSample.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) { 
		        	int sourceStatus = checkSource();
		    		if (sourceStatus==0)	return;
		    		ImagePlus source = getCurrentSource();
		    		ImagePlus target = window.getImagePlus();
		    		//int addIdx = source.getCurrentSlice();
		    		int addIdx = source.getZ();
		    		if (!HyperStackUtility.addSlice(source, target, 4, addIdx, 0, true))
		    			System.out.println("Can not add sample " + addIdx + " in " + source.getTitle() + " to " + target.getTitle());
		    		updateTrainingSampleWindow();
		        }
		    }); 
		    return addSample;
		}
		// create delete sample button
		public Button addDelButton(ImageWindow window) {
		    Button delSample = new Button("- Sample"); 
		    delSample.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) { 
		        	ImagePlus imp = window.getImagePlus();
		        	boolean isSource = imp.equals(getCurrentSource());
		        	int delIdx = imp.getZ();
		        	if (imp.getNSlices()==1)	{ // only 1 sample in class, delete class after successful slice deletion
		        		String title = "Warning: Class Deletion (" + imp.getTitle() + ")!";
		        		String body = "Last sample in class " + imp.getTitle() + 
		        				"\nClass will also be removed after deletion of the sample.";
		        		if (!IJ.showMessageWithCancel(title, body))	return;
		        	}
		        	if (!HyperStackUtility.deleteSlice(imp, 4, delIdx)) {
	        			System.out.println("Can not delete sample " + delIdx + " in " + imp.getTitle());
	        		}
		        	if (isSource)
		        		updateSourceWindow();
		        	else
		        		updateTrainingSampleWindow();
		        }
		    }); 
		    return delSample;
		}
		// create move sample button
		public Button addMoveClassButton(
				ImageWindow window,
				String className
				) {
		    Button moveSample = new Button("Move to "+className); 
		    moveSample.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) { 
		        	// check input className
		        	ImagePlus target = null;
		        	for (ImagePlus trainingSample : trainingSamples) {
		        		if (trainingSample.getTitle().equals(className))
		        			target = trainingSample;
		        	}
		        	if (target==null)	return;
		        	ImagePlus source = window.getImagePlus();
		        	//
		        	int addIdx = source.getZ();
		    		if (!HyperStackUtility.addSlice(source, target, 4, addIdx, 0, false)) {
		    			System.out.println("Can not add sample " + addIdx + 
		    					" in " + source.getTitle() + 
		    					" to " + target.getTitle());
		    		}	
		    		updateTrainingSampleWindow();
		        } 
		    }); 
		    return moveSample;
		}
		// delete move sample button
		public void deleteMoveClassButton(
				ImageWindow window,
				String className
				) {
			JPanel buttonPanel = getPanel(window, "classSetupPanel");
			if (buttonPanel==null)  return;
			Button delete = getButton(window, "Move to " + className);
			if (delete==null)	return;
			buttonPanel.remove(delete);
			window.pack();
		}
		// rename move sample button
		public void renameMoveClassButton(
				ImageWindow window,
				String oldClassName,
				String newClassName
				) {
			JPanel buttonPanel = getPanel(window, "classSetupPanel");
			if (buttonPanel==null)  return;
			Button rename = getButton(window, "Move to " + oldClassName);
			if (rename==null)	return;
			rename.setLabel("Move to " + newClassName);
			rename.addActionListener(new ActionListener() { 
		        @Override 
		        public void actionPerformed(ActionEvent ae) { 
		        	// check input className
		        	ImagePlus target = null;
		        	for (ImagePlus trainingSample : trainingSamples) {
		        		if (trainingSample.getTitle().equals(newClassName))
		        			target = trainingSample;
		        	}
		        	if (target==null)	return;
		        	ImagePlus source = window.getImagePlus();
		        	int addIdx = source.getCurrentSlice();
		    		if (!HyperStackUtility.addSlice(source, target, 4, addIdx, 0, false)) {
		    			System.out.println("Can not add sample " + addIdx + 
		    					" in " + source.getTitle() + 
		    					" to " + target.getTitle());
		    		}		
		        } 
		    }); 
			window.pack();
		}
	
/*
 * Function groups to check training sample classes
 */
	// currently unused
	public void updateAndRepaintWindows() {
		if (getNClasses()==0) return;
		for (ImagePlus trainingSample : trainingSamples) {
			ImageWindow window = trainingSample.getWindow();	
		}
		
	}
	
	// function to get number of classes from TrainingSamples Object
	public int getNClasses() {
		return this.trainingSamples.size();
	}
	// function to get number of training samples (2D images) for given class
	public int getNSamples(String className) {
		if (getNClasses()==0) return 0;
		for (ImagePlus trainingSample : this.trainingSamples) {
			if (trainingSample.getTitle().equals(className))
				return trainingSample.getNSlices();
		}
		return 0;
	}
	// function to get class names into a String list
	public ArrayList<String> getClassNames() {
		if (getNClasses()==0) return null;
		ArrayList<String> classNames = new ArrayList<String>();
		for (ImagePlus trainingSample : this.trainingSamples) {
			classNames.add(trainingSample.getTitle());
		}
		return classNames;
	}
	public boolean classExist(String className) {
		ArrayList<String> classNames = getClassNames();
		if (classNames==null)	return false;
		return classNames.contains(className);
	}
	
	public void resize(int newSizeInPixel) {
		this.imageSize = newSizeInPixel;
	}

	
//
 // Function groups for stack slice operation together with overlay:
 //  add, delete, replace, move, check duplicate
 //
	// function to add slice from source to target, can keep or delete the added slice from source
	/*
	public boolean addSlice(
			ImagePlus source,
			ImagePlus target,
			int sourceIndex,		// 1-based Z index
			//ArrayList<Integer> moveIndexs,
			boolean keepSliceInSource
			) {
		// Control the input parameters:
		// 	both source and target image need to be exist;
		//  source need to contain at least 1 slice;
		//  move index need to be inside the range of source slices
		if (source==null || source.getNSlices()==0) return false;
		if (target==null) return false;
		int nSource = source.getNSlices();
		int nTarget = target.getNSlices();
		//if (moveIndexs==null || moveIndexs.size()<1)	return false;

		if (sourceIndex<=0 || sourceIndex>nSource) return false;

		boolean resize = false;
		if (source.getWidth()!=target.getWidth() || source.getHeight()!=target.getHeight()) {
			IJ.log("   Size mismatch between " + source.getTitle() + " and " + target.getTitle() + "!");
			resize = true;
		}
		// add slice to target with label and overlay, repaint window
		//ImagePlus sliceImp = new ImagePlus("slice", source.getStack().getProcessor(sourceIndex).duplicate());
		ImagePlus sliceImp = new Duplicator().run(source, 1, source.getNChannels(), sourceIndex, sourceIndex, 1, source.getNFrames());
		int nSlices = sliceImp.getStackSize();
		sliceImp.setTitle("slice");
		
		if (resize) {
			IJ.run(sliceImp, "Size...", "width=["+target.getWidth()+"] height=["+target.getHeight()+"] average interpolation=Bicubic");
		}
		// check if the slice has already exist in the target
		int dupSlice = checkDuplicate(sliceImp, target, 0);
		if (dupSlice!=0) {
			//IJ.error("sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			System.out.println("Sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			target.setZ(dupSlice);
			return false;
		}
		// update the overlay on both source and target stacks
		
		Overlay sourceOverlay = source.getOverlay().duplicate();
		Overlay targetOverlay = target.getOverlay();
		if (targetOverlay==null) targetOverlay = new Overlay();
		Overlay sliceOverlay = new Overlay();
		if (sourceOverlay!=null) sliceOverlay = sourceOverlay.duplicate();
		sliceOverlay.crop(moveIndex, moveIndex);
		Roi[] overlayRois = sliceOverlay.toArray();
		for (Roi overlayRoi : overlayRois) {
			if (resize) {
				overlayRoi = RoiScaler.scale(overlayRoi, target.getWidth()/source.getWidth(), target.getHeight()/source.getHeight(), true);
				overlayRoi.setLocation(
						(double)((target.getWidth()-overlayRoi.getBounds().width)/2), 
						(double)((target.getHeight()-overlayRoi.getBounds().height)/2));  // center current ROI
			}
			overlayRoi.setPosition(nTarget+1);
			targetOverlay.add(overlayRoi);
		}
		Overlay newSourceOverlay = new Overlay();
		if (sourceOverlay!=null) {
			Roi[] sourceRois = sourceOverlay.toArray();
			for (Roi sourceRoi : sourceRois) {
				int roiPosition = sourceRoi.getPosition();
				if (roiPosition < moveIndex) {
					newSourceOverlay.add(sourceRoi);	// roi position before move index stay the same
				} else if (roiPosition > moveIndex) {
					sourceRoi.setPosition(roiPosition-1);	// roi position after move index move 1 forward
					newSourceOverlay.add(sourceRoi);
				}	// roi position at move index will be removed from array
			}
		}

		// add slice to target stack, update target overlay, repaint window
		ImageStack sourceStack = source.getStack();
		ImageStack targetStack = target.getStack();
		// update slice label, add source slice index in the end
		String sourceSliceLabel = sourceStack.getSliceLabel(sourceIndex);
		String targetSliceLabel = "";
		int labelIndex = sourceSliceLabel.lastIndexOf(", Slice:");
		if (labelIndex==-1) {	// source label doesn't exist;
			targetSliceLabel = sourceSliceLabel + ", Slice:" + String.valueOf(sourceIndex);
		} else {	// source label exist, then update it
			targetSliceLabel = sourceSliceLabel.substring(0, labelIndex) + ", Slice:" + String.valueOf(sourceIndex);
		}
		for (int i=1; i<=nSlices; i++) {
			targetStack.addSlice(targetSliceLabel, sliceImp.getStack().getProcessor(i));
		}

		//String targetName = target.getTitle();
		int sizeC = target.getNChannels();
		int sizeZ = target.getNSlices();
		int sizeT = target.getNFrames();
		ImagePlus newTarget = new Concatenator().concatenate(target, sliceImp, true);
		newTarget = HyperStackConverter.toHyperStack(newTarget, sizeC, sizeZ+1, sizeT, "composite");
		newTarget.setTitle(target.getTitle()); newTarget.setOverlay(target.getOverlay());
		target.setImage(newTarget);

    	//target.setOverlay(targetOverlay);
    	copyOverlay(source, target, sourceIndex, nTarget+1);	// only copy(replace) to target without updating the source
    	
    	//target.setTitle(targetName);
    	target.setZ(nTarget+1);
    	target.changes = true;
    	//target.show();
    	target.getWindow().updateImage(target);
    	target.getWindow().pack();
    	//updateTrainingSampleWindow();
    	// if keep slice, finised 
    	// otherwise delete the slice from source, 
    	// update the source overlay, repaint window
    	if (!keepSliceInSource) {
    		if (nSource==1) {
    			source.changes = true;
    			source.close();
    			System.gc();
    		} else {
    			deleteSlice(source, sourceIndex);
    		}
    	}
		return true;
	}
	*/
	
	// function to delete current displayed slice in stack, together with overlay
	/*
	public boolean deleteSlice(
			ImagePlus source,
			int deleteIndex		// 1-based slice index, using Z
			) {
		if (source==null || source.getNSlices()==0) return false;
		int nSource = source.getNSlices();
		if (deleteIndex<=0 || deleteIndex>nSource) return false;
		if (nSource==1) {
			removeClass(source.getTitle());
			return true;
		}
		ImageStack sourceStack = source.getStack();
		int numC = source.getNChannels(); int numZ = source.getNSlices(); int numT = source.getNFrames();
		Overlay sourceOverlay = source.getOverlay();
		Overlay newSourceOverlay = new Overlay();
		if (sourceOverlay!=null) {
			Roi[] sourceRois = sourceOverlay.toArray();
			for (Roi sourceRoi : sourceRois) {
				int roiPosition = sourceRoi.getZPosition();
				if (roiPosition < deleteIndex) {
					newSourceOverlay.add(sourceRoi);	// roi position before delete index stay the same
				} else if (roiPosition > deleteIndex) {
					if (source.isHyperStack())
						sourceRoi.setPosition(0, roiPosition-1, 0);	// roi position after delete index move 1 forward
					else
						sourceRoi.setPosition(roiPosition-1);
					newSourceOverlay.add(sourceRoi);
				}	// roi position at delete index will be removed from array
			}
		}
		for (int t=numT; t>=1; t--) {
			int index = source.getStackIndex(numC, deleteIndex, t);
			for (int c=0; c<numC; c++)
				sourceStack.deleteSlice(index-c);
		}
		//sourceStack.deleteSlice(deleteIndex);
		source.setStack(sourceStack, numC, (numZ-1), numT);
		source.setOverlay(newSourceOverlay);
		source.setZ(deleteIndex==1 ? 1 : (deleteIndex-1));
		source.changes = true;
		source.getWindow().updateImage(source);
		source.getWindow().pack();
		return true;
	}
	*/
	//	function to replace a certain slice in stack, together with overlay
	/*
	public boolean replaceSlice (
			ImagePlus source,
			ImagePlus target,
			int sourceIndex,	// 1-based slice index, currently using Z index
			int targetIndex,	// 1-based slice index, currently using Z index
			boolean keepSliceInSource
			) {
		// Control the input parameters:
		// 	both source and target image need to be exist;
		//  source need to contain at least 1 slice;
		//  move index need to be inside the range of source slices
		if (source==null || source.getNSlices()==0) return false;
		if (target==null) return false;
		int nSource = source.getNSlices();
		int nTarget = target.getNSlices();
		if (sourceIndex<=0 || sourceIndex>nSource || targetIndex<=0 || targetIndex>nTarget) return false;
		
		boolean resize = false;
		if (source.getWidth()!=target.getWidth() || source.getHeight()!=target.getHeight()) {
			System.out.println("   Size mismatch between " + source.getTitle() + " and " + target.getTitle() + "!");
			resize = true;
		}
		// add slice to target with label and overlay, repaint window
		//ImagePlus sliceImp = new ImagePlus("slice", source.getStack().getProcessor(sourceIndex).duplicate());
		ImagePlus sliceImp = new Duplicator().run(source, 1, source.getNChannels(), sourceIndex, sourceIndex, 1, source.getNFrames());
		int nSlices = sliceImp.getStackSize();
		sliceImp.setTitle("slice");
		
		if (resize) {
			//IJ.run(sliceImp, "Size...", "width=["+target.getWidth()+"] height=["+target.getHeight()+"] depth=1 average interpolation=Bicubic");
			IJ.run(sliceImp, "Size...", "width=["+target.getWidth()+"] height=["+target.getHeight()+"] average interpolation=Bicubic");
		}
		// check if the slice has already exist in the target
		int dupSlice = checkDuplicate(sliceImp, target, targetIndex);
		if (dupSlice!=0) {
			//IJ.error("sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			System.out.println("Sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			target.setSlice(dupSlice);
			return false;
		}
		// add slice to target stack, update target overlay, repaint window
		ImageStack sourceStack = source.getStack();
		ImageStack targetStack = target.getStack();
		int nEnd = target.getStackIndex(1, targetIndex, 1);
		
		for (int i=1; i<=nSlices; i++) {
			targetStack.setProcessor(sliceImp.getStack().getProcessor(i), nEnd+i-1);
			// update slice label, add source slice index in the end
			String sourceSliceLabel = sourceStack.getSliceLabel(sourceIndex);
			String targetSliceLabel = "";
			int labelIndex = sourceSliceLabel.lastIndexOf(", Slice:");
			if (labelIndex==-1) {	// source label doesn't exist;
				targetSliceLabel = sourceSliceLabel + ", Slice:" + String.valueOf(sourceIndex);
			} else {	// source label exist, then update it
				targetSliceLabel = sourceSliceLabel.substring(0, labelIndex) + ", Slice:" + String.valueOf(sourceIndex);
			}
			targetStack.setSliceLabel(targetSliceLabel, targetIndex);
		}
		//targetStack.addSlice(sourceStack.getSliceLabel(sourceIndex), sliceImp.getProcessor());
    	target.setStack(targetStack);
    	copyOverlay(source, target, sourceIndex, targetIndex);	// only copy(replace) to target without updating the source
    	//target.setOverlay(targetOverlay);
    	target.setSlice(nTarget+1);
    	target.changes = true;
    	target.getWindow().updateImage(target);
    	target.getWindow().pack();
    	// if keep slice, finised 
    	// otherwise delete the slice from source, 
    	// update the source overlay, repaint window
    	if (!keepSliceInSource) {
    		if (nSource==1) {
    			source.changes = true;
    			source.close();
    			System.gc();
    		} else {
    			if (!deleteSlice(source, sourceIndex))
    				System.out.println("  Could not delete slice " + sourceIndex + " in source image " + source.getTitle());
    		}
    	}
		return true;
	}
	*/
	// check if a slice had already exist in a stack: 
	//  based on pixel-wise comparison, don't account for transform
	/*
	public int checkDuplicate(
			ImagePlus impSlice, 
			ImagePlus impStack,
			int sliceNum	// if sliceNum is 0, compare all stack slices, otherwise compare a specific slice at sliceNum in stack
			) {
		// if sliceNum is -1, run self-compare to remove duplicated slices in stack
		if (sliceNum==-1) {
			ImageCalculator ic = new ImageCalculator();
			ImagePlus sliceImage = null;
			ImagePlus impDiff = null;
			ImageStack stack = impStack.getStack();
			int nSlices = impStack.getNSlices();
			ArrayList<Integer> sliceIdx = new ArrayList<Integer>();
			//ArrayList<Integer> sliceIdx2 = new ArrayList<Integer>();	// rest list
			for (int i=0; i<nSlices; i++) {
				sliceIdx.add(i+1);
				//sliceIdx2.add(i+1);
			}
			ArrayList<Integer> deleteIdx = new ArrayList<Integer>();			
			Iterator<Integer> iter = sliceIdx.iterator();
			while (iter.hasNext()) {
				Integer idx = iter.next();
				if (deleteIdx.contains(idx)) continue;	// idx already in remove list, continue
				//sliceIdx.remove(idx);	// remove current index from rest list
				iter.remove();
				sliceImage =  new ImagePlus(null, stack.getProcessor(idx));	// get a new slice image
				for (Integer idx2 : sliceIdx) {	// check through all the rest image
					if (deleteIdx.contains(idx2)) continue;
					//if (deleteIdx.contains(idx2) || idx2==idx) continue;
					impDiff = ic.run("Subtract create", sliceImage, new ImagePlus("null", stack.getProcessor(idx2)));
					ImageStatistics stats = impDiff.getProcessor().getStats();
					if (stats.max == stats.min)	// duplicate found
						deleteIdx.add(idx2);
				}
			}
			Collections.sort(deleteIdx); Collections.reverse(deleteIdx);
			for (Integer del : deleteIdx) {
				deleteSlice(impStack, del);
			}
			return sliceNum;
		}
		if (impSlice==null || impStack==null) return 0;
		int nSlices = impStack.getStackSize();
		ImageCalculator ic = new ImageCalculator();
		ImagePlus impDiff = null;
		if (sliceNum>0 && sliceNum<=impStack.getStackSize()) {	// only compare 1 slice
			impDiff = ic.run("Subtract create", impSlice, new ImagePlus("checkDupStackSlice"+String.valueOf(sliceNum+1), impStack.getStack().getProcessor(sliceNum)));
			ImageStatistics stats = impDiff.getProcessor().getStats();
			if (stats.max == stats.min) {
				impDiff.changes=false; impDiff.close(); System.gc();
				int[] stackPosition = impStack.convertIndexToPosition(sliceNum);	// convert slice index to hyperstack position
				return stackPosition[1];	// slice exist in stack at Z
			}
		} else {
			for (int i=0; i<nSlices; i++) {
				//impStack.setSliceWithoutUpdate(i+1);
				impDiff = ic.run("Subtract create", impSlice, new ImagePlus("checkDupStackSlice"+String.valueOf(i+1), impStack.getStack().getProcessor(i+1)));
				ImageStatistics stats = impDiff.getProcessor().getStats();
				if (stats.max == stats.min) {
					impDiff.changes=false; impDiff.close(); System.gc();
					int[] stackPosition = impStack.convertIndexToPosition(i+1);	// convert slice index to hyperstack position
					return stackPosition[1];	// slice exist in stack at Z
				}
			}
			impDiff.changes=false; impDiff.close(); System.gc();
		}
		return 0;	// slice not exist in stack
	}
	*/

/*
 * Function group for create/manage ROI/overlay/histogram/montage of image(stack)
 */
	// function to draw cell ROI based on auto-threshold method
	public void makeCellRoi (ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
		imp.setRoi(getPrincipleObjRoi(imp));
	}
	// function to enlarge/shrink cell ROI by 1 pixel
	public void enlargeCellRoi (ImageWindow window, boolean enlarge) {
		ImagePlus imp = window.getImagePlus();
		Roi roi = imp.getRoi();
		if (roi==null) return;
		imp.setRoi(RoiEnlarger.enlarge(roi, enlarge?1:-1));
	}
	// function to perform overlay actions
	public void overlayAction (ImageWindow window) {
		if (overlayButtonAction==1 && window.getImagePlus().getRoi()!=null) {
			cellRoiToOverlay(window);
		} else {
			showHideOverlay(window);
		}
	}
	// function to show/hide overlay
	public void showHideOverlay (ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
		imp.setHideOverlay(!imp.getHideOverlay());
	}
	// function to generate overlay from cell ROI
	public void cellRoiToOverlay (ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
		if (imp.getRoi()==null)	return;
		addRoiToOverlay(imp, imp.getRoi());
		imp.deleteRoi();
	}
	// function to setup the ROI/Overlay functions
	public void overlaySetup (ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
		overlaySetup(imp);
	}
	// function to create histogram from image and overlay(ROI)
	public static void makeHistogram(ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
    	LUT[] oriLUT = imp.getLuts();
    	int posC = imp.getC(); int posZ = imp.getZ(); int posT = imp.getT();
    	int currentIdx = imp.getCurrentSlice();
    	Overlay newOverlay = imp.getOverlay().duplicate();
    	newOverlay.crop(posC, posC, posZ, posZ, posT, posT);
    	Roi[] overlayRois = newOverlay.toArray();
    	ImageProcessor ip = imp.getProcessor();
		ImagePlus impSlice = new ImagePlus("current slice", ip);
		for (int i=0; i<overlayRois.length; i++) {
			impSlice.setLut(LUT.createLutFromColor(overlayRois[i].getStrokeColor()));
			impSlice.setRoi(overlayRois[i]);
			HistogramWindow histo = new HistogramWindow(imp.getTitle()+"histogram "+String.valueOf(i+1), impSlice, impSlice.getRawStatistics());
			histo.setLocation((int)(1+window.getBounds().getX() + (i+1)*window.getBounds().getWidth()), (int)window.getBounds().getY());
			histo.setTitle("Slice:"+String.valueOf(imp.getCurrentSlice())+", "+ overlayRois[i].getName());
		}
		impSlice.changes = true; impSlice.close(); imp.setLut(oriLUT[0]);
	}
	// function to close all histogram belongs to a image(stack)
	public static void closeAllHistogram(ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
    	String title = imp.getTitle();
    	String[] imageNames = WindowManager.getImageTitles();
    	for (int i=0; i<imageNames.length; i++) {
    		if (imageNames[i].contains(title) && imageNames[i].toLowerCase().contains("histogram")) {
    			WindowManager.getImage(imageNames[i]).changes=false; 
    			WindowManager.getImage(imageNames[i]).close();
    		}
    	}
	}
	// function to create gallery from image (without overlay)
	public static void makeGallery(ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
    	int column = 10;
    	double scale = 1;
    	boolean showLabel = true;
		GenericDialog gd = new GenericDialog("configure gallery layout");
		gd.addMessage("sample image number: " + String.valueOf(imp.getStack().size()));
		gd.addNumericField("Column: ", column, 0);
		gd.addSlider("Zoom: ", 50, 500, scale*100, 50);
		gd.addCheckbox("Label: ", showLabel);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		column = (int)gd.getNextNumber();
		if (column<1)	return;
		scale = gd.getNextNumber()/100;
		showLabel = gd.getNextBoolean();
    	ImagePlus galleryImp = makeMontage(imp, showLabel, column, scale);
    	if (galleryImp!=null) {
    		galleryImp.show();
	    	//  add key listener to gallery image canvas, so mouse click to a certain part will update the one in the source image stack
    		final int height = (int) (imp.getHeight()*scale);
    		final int width = (int) (imp.getWidth()*scale);
    		final int border = montageBorderWidth;
    		final int col = column;
	    	galleryImp.getCanvas().addMouseListener(new MouseListener() {
				@Override
				public void mouseClicked(MouseEvent arg0) {
					Point cursorPoint = galleryImp.getCanvas().getCursorLoc();
					double x = cursorPoint.getX(); double y = cursorPoint.getY();
					int nRow = Math.floorDiv((int) y, height+border);
					int nCol = 1 + Math.floorDiv((int) x, width+border);
					int nSlice = nRow * col + nCol;
					if (nSlice <= imp.getNSlices())
						imp.setZ(nSlice);
					imp.setC(galleryImp.getC());
					imp.setT(galleryImp.getT());
				}
				@Override
				public void mouseEntered(MouseEvent arg0) {}
				@Override
				public void mouseExited(MouseEvent arg0) {}
				@Override
				public void mousePressed(MouseEvent arg0) {}
				@Override
				public void mouseReleased(MouseEvent arg0) {}
	    	});
    	}
	}
	// function to close all gallery belongs to a image(stack)
	public static void closeAllGallery(ImageWindow window) {
		ImagePlus imp = window.getImagePlus();
    	String title = imp.getTitle();
    	String[] imageNames = WindowManager.getImageTitles();
    	for (int i=0; i<imageNames.length; i++) {
    		if (imageNames[i].contains(title) && imageNames[i].toLowerCase().contains("gallery")) {
    			WindowManager.getImage(imageNames[i]).changes=false; 
    			WindowManager.getImage(imageNames[i]).close();
    		}
    	}
	}
	// function to make montage from image
	public static ImagePlus makeMontage(
			ImagePlus imp,
			boolean showLabel,
			int column,
			double scale
			) {
		ImagePlus montage = null;
		if (!imp.isStack())	return montage;
		int numImages = imp.getNSlices();
		if (numImages==1)	return montage;
		if (column<1)	return montage;
		ImagePlus impDup = imp.duplicate();
		ImageStack stack = impDup.getStack();
		if (showLabel) {
			for (int s=1; s<=stack.getSize(); s++) {
				String indexStr = String.valueOf(imp.convertIndexToPosition(s)[1]);	// by default using Z as sample index
				String sourceSliceLabel = stack.getSliceLabel(s);	// get source slice label
				int idx = sourceSliceLabel.indexOf(", Slice:");		// locate slice index part
				String sourceSliceLabelPart = idx==-1 ? "NA" : sourceSliceLabel.substring(idx+8, sourceSliceLabel.length());
				String[] digitParts = sourceSliceLabelPart.split("\\D");	// locate digit parts
				String sourceSlice = digitParts.length==0 ? "NA" : digitParts[0];	// parse the digit as source slice index
				stack.setSliceLabel(indexStr + ", source:" + sourceSlice, s);
			}
		}
		
		int row = (int)(Math.ceil((double)(numImages)/(double)(column)));
		MontageMaker mm2 = new MontageMaker();
		mm2.setFontSize((int) (24*scale));
		montage = makeHyperstackMontage(impDup, column, row, scale, 1, montageBorderWidth, showLabel, (imp.getTitle() + " gallery"), (int)(24*scale));
		impDup.changes=false; impDup.close(); System.gc();
		return montage;
	}
	
	/** Creates a hyperstack montage and returns it as an ImagePlus. */
    public static ImagePlus makeHyperstackMontage(
    		ImagePlus imp, 
    		int columns, int rows, double scale, int inc, int borderWidth, boolean labels,
    		String montageName, int fontSize
    		) {
        ImagePlus[] channels = ChannelSplitter.split(imp);
        int n = channels.length;
        ImagePlus[] montages = new ImagePlus[n];
        for (int i=0; i<n; i++) {
            int last = channels[i].getStackSize();
            MontageMaker mm = new MontageMaker();
            mm.setFontSize(fontSize);
            montages[i] = mm.makeMontage2(channels[i], columns, rows, scale, 1, last, inc, borderWidth, labels);
        }
        if (n==1) {
        	montages[0].setTitle(montageName);
        	return montages[0];
        }
        ImagePlus montage = (new RGBStackMerge()).mergeHyperstacks(montages, false);
        montage.setCalibration(montages[0].getCalibration());
        montage.setTitle(montageName);
        return montage;
    }
	// class to handel montage (gallery) images
	/* HERE!!!
	public class montageImage extends ImagePlus implements ImageListener {
		// constructor
		public montageImage() {
			this("Gallery Image");
		}
		public montageImage(String s) {
			super(s);
			ijInstance = IJ.getInstance();
			if (instance!=null) {
				WindowManager.toFront(instance);
				return;
			}
			instance = this;
			panel = controlPanel();
			add(panel);
			GUI.scale(this);
			pack();
			setResizable(false);
			IJ.register(this.getClass());
			if (location==null)
				location = getLocation();
			else
				setLocation(location);
			updateWindowList();
			WindowManager.addWindow(this);
			ImagePlus.addImageListener(this);
			Executer.addCommandListener(this);
			show();
		}


		@Override
		public void imageOpened(ImagePlus imp) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void imageClosed(ImagePlus imp) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void imageUpdated(ImagePlus imp) {
			// TODO Auto-generated method stub
			
		}
		
	}
	*/
	
	// function to create auto cell ROI (and overlays)
	public Roi getPrincipleObjRoi(
			ImagePlus imp // input image
			) {
		// get 2D image from input image, crop margin, convert to mask
		int oriWidth = imp.getWidth();
		int oriHeight = imp.getHeight();
		Roi roi = imp.getRoi(); imp.deleteRoi();
		ImagePlus mask = imp.crop(); imp.setRoi(roi);
		IJ.run(mask, "Auto Crop (guess background color)", "");	// get rid of black margins in the original image
		int width = mask.getWidth(); double xOffset = (oriWidth-width)/2;
		int height = mask.getHeight(); double yOffset = (oriHeight-height)/2;
		int filterSize = (int)(Math.sqrt(width*height)/50);
		ImageProcessor ip = mask.getProcessor();
		if (!ip.isBinary()) {	// threshold image first
			ip.setAutoThreshold(thresholdMethod, true, ImageProcessor.NO_LUT_UPDATE);		
			ByteProcessor bp = ip.createMask();
			mask = new ImagePlus("mask", bp);
		}
		// smooth mask edge
		IJ.run(mask, "Canvas Size...", "width=["+(width+2*filterSize)+"] height=["+(height+2*filterSize)+"] position=Center zero");
		IJ.run(mask, "Median...", "radius=["+filterSize+"]");
		ManyBlobs allBlobs = new ManyBlobs(mask); // Extended ArrayList
		allBlobs.setBackground(0); // 0 for black, 1 for 255
		allBlobs.findConnectedComponents(); // Start the Connected Component Algorithm
		int blobIdx = 0;
		//double area = allBlobs.get(blobIdx).getEnclosedArea(); // in case want to filter by size
		if (allBlobs.size()==0)	return null;
		if (allBlobs.size()>1) {	// more than 1 objects in mask
			double area = 0.0;
			for (int i=0; i<allBlobs.size(); i++) {
				if (allBlobs.get(i).getEnclosedArea()>area) {
					blobIdx = i;
					area = allBlobs.get(i).getEnclosedArea();
				}
			}
		}
		mask.changes=false; mask.close(); System.gc();
		Polygon p = allBlobs.get(blobIdx).getOuterContour();
	    int n = p.npoints;
	    float[] x = new float[p.npoints];
	    float[] y = new float[p.npoints];   
	    for (int j=0; j<n; j++) {
	        x[j] = p.xpoints[j]+0.5f;
	        y[j] = p.ypoints[j]+0.5f;
	    }
	    Roi blobRoi = new PolygonRoi(x,y,n,Roi.FREEROI);	//!!! The original TRACED_ROI doesn't work with slice overlay manipulations !!!
	    blobRoi.setStrokeColor(Color.YELLOW);
	    blobRoi.setLocation(blobRoi.getXBase()-(double)filterSize+xOffset, blobRoi.getYBase()-(double)filterSize+yOffset);
		return blobRoi;
	}
	
	// function to get x and y scale, plus edge size in pixel of the original image
	public double[] getEdgeSizeInPixel (ImagePlus imp) {
		double pxSize = 0.2; // default pixel size is 0.2 micron per pixel
		double xScale = 1.0; double yScale = 1.0;
		double[] result = {xScale, yScale, edgeSizeInMicron/pxSize};
		if (imp==null) {
			System.out.println("Image Empty!");
			return result;
		}
		String label = imp.getStack().getSliceLabel(imp.getCurrentSlice());
		int scaleIdx = label.indexOf("Scale:");
		if (scaleIdx==-1) {
			System.out.println("Scale string not detected in image " + imp.getTitle());
			return result;
		}
		String[] scaleString = label.substring(scaleIdx+6, label.length()).split(",");
		if (scaleString.length!=3) {
			System.out.println("Scale string format wrong in image " + imp.getTitle());
			return result;
		}
		String pxSizeString = scaleString[0];
		String xScaleString = scaleString[1];
		String yScaleString = scaleString[2];
		int pxSizeIdx = pxSizeString.indexOf("micron") * pxSizeString.indexOf("um") * pxSizeString.indexOf("µm");
		if (pxSizeIdx!=-1)
			pxSize = Double.valueOf(pxSizeString.substring(0, pxSizeIdx));
		else
			System.out.println(imp.getTitle()+"is not calibrated! Use 0.2um/pixel as default calibration!");
		xScale = Double.valueOf(xScaleString.substring(1, xScaleString.length()));
		yScale = Double.valueOf(yScaleString.substring(1, yScaleString.length()));
		//xScale: scaleFactorX = (double)imageSize/(double)imp.getWidth(); 	// current/original
		//yScale: scaleFactorY = (double)imageSize/(double)imp.getHeight();	// current/original
		return new double[]{xScale, yScale, edgeSizeInMicron/pxSize};
	}
	
	/*
	// depracted! function to get edge size in pixel of the scaled image
	public double[] getEdgeSizeInPixel2 (ImagePlus imp) {
		double pxSize = 0.2; // default pixel size is 0.2 micron per pixel
		double xScale = 1.0; double yScale = 1.0;
		double[] result = {xScale*edgeSizeInMicron/pxSize, yScale*edgeSizeInMicron/pxSize};
		if (imp==null) {
			System.out.println("Image Empty!");
			return result;
		}
		String label = imp.getStack().getSliceLabel(imp.getCurrentSlice());
		int scaleIdx = label.indexOf("Scale:");
		if (scaleIdx==-1) {
			System.out.println("Scale string not detected in image " + imp.getTitle());
			return result;
		}
		String[] scaleString = label.substring(scaleIdx+6, label.length()).split(",");
		if (scaleString.length!=3) {
			System.out.println("Scale string format wrong in image " + imp.getTitle());
			return result;
		}
		String pxSizeString = scaleString[0];
		String xScaleString = scaleString[1];
		String yScaleString = scaleString[2];
		int pxSizeIdx = pxSizeString.indexOf("micron") * pxSizeString.indexOf("um") * pxSizeString.indexOf("µm");
		if (pxSizeIdx!=-1)
			pxSize = Double.valueOf(pxSizeString.substring(0, pxSizeIdx));
		else
			System.out.println(imp.getTitle()+"is not calibrated! Use 0.2um/pixel as default calibration!");
		xScale = Double.valueOf(xScaleString.substring(1, xScaleString.length()));
		yScale = Double.valueOf(yScaleString.substring(1, yScaleString.length()));
		return new double[]{xScale*edgeSizeInMicron/pxSize, yScale*edgeSizeInMicron/pxSize};
	}
	*/
	/*
	// depracted! function to guess edge size in pixel
	public int guessEdgeSize (ImagePlus imp) {
		if (imp==null || imp.getOverlay()==null) return 0;
		Overlay overlay = imp.getOverlay().duplicate();
		overlay.crop(imp.getCurrentSlice(), imp.getCurrentSlice());
		if (overlay==null || overlay.size()==0)	return 0;
		Roi[] rois = overlay.toArray();
		int allIdx = 0; int centerIdx = 0;
		//Roi all = rois[0]; Roi center = rois[0];
		for (int i=0; i<rois.length; i++) {
			if (rois[i].getName().toLowerCase().equals("all"))
				allIdx = i;
			else if (rois[i].getName().toLowerCase().equals("center"))
				centerIdx = i;
		}
		if (allIdx==centerIdx) return 0;
		Roi oriRoi = imp.getRoi();
		imp.setRoi(rois[centerIdx], false);
		double areaCenter = imp.getProcessor().getStats().area;
		imp.setRoi(rois[allIdx], false);
		double areaAll = imp.getProcessor().getStats().area;
		imp.deleteRoi();
		Roi shrinkAll = (Roi) rois[allIdx].clone();
		double edgeSize = 1.0; // start with 1 pixel
		double shrinkedAreaAll = areaAll;
		//areaCenter=0.0;
		//IJ.log("debug shrink ROI: areaCenter: " + String.valueOf(areaCenter));
		while(areaCenter<shrinkedAreaAll) {	//HERE!!!
			//IJ.log("debug shrink ROI: areaAll: " + String.valueOf(areaAll));
			shrinkAll = RoiEnlarger.enlarge(rois[allIdx], -edgeSize);
			imp.setRoi(shrinkAll, false);
			shrinkedAreaAll = imp.getProcessor().getStats().area;
			imp.deleteRoi();
			if (shrinkedAreaAll==areaAll) {	// shrinked to 1 pixel width
				break;
			} else {
				areaAll = shrinkedAreaAll;
			}
			edgeSize += 1.0;
		}
		imp.setRoi(oriRoi);
		edgeSize -= 1.0;
		//IJ.log("debug shrink ROI: edgeSize: " + String.valueOf(edgeSize));
		//IJ.log("debug shrink ROI: (int)edgeSize: " + String.valueOf((int)edgeSize));
		return (int)edgeSize;
	}
	*/
	// function to copy overlay between image stacks
	/*
	public void copyOverlay (	// only copy(replace) to target without updating the source
			ImagePlus source,
			ImagePlus target,
			int sourceIdx,	// 1-based Z index
			int targetIdx	// 1-based Z index
			) {
		if (source==null || target==null)	return; // no source or target image
		int nSource = source.getNSlices(); int nTarget = target.getNSlices();
		if (sourceIdx<1 || sourceIdx>nSource || targetIdx<1 || targetIdx>nTarget)	return; // control move index in range of source and target
		
		int sizeC = source.getNChannels(); int sizeZ = source.getNSlices(); int sizeT = source.getNFrames();
		
		Overlay sourceOverlay = source.getOverlay()==null ? null : source.getOverlay().duplicate();
		Overlay targetOverlay = target.getOverlay()==null ? null : target.getOverlay().duplicate();
		
		if (sourceOverlay==null)	return; // no overlay in source
		if (targetOverlay==null) {	// prepare target overlay, remove the target index
			targetOverlay = new Overlay();
		} else {
			Roi[] targetRois = targetOverlay.toArray();
			for (Roi targetRoi : targetRois) {
				
				if (targetRoi.getZPosition()==targetIdx) {
					targetOverlay.remove(targetRoi);
				}
			}
		}
		
		boolean resize = (source.getWidth()!=target.getHeight() || source.getHeight()!=target.getHeight());
		Overlay sliceOverlay = new Overlay();
		sliceOverlay = sourceOverlay.duplicate();
		sliceOverlay.crop(1, sizeC, sourceIdx, sourceIdx, 1, sizeT);
		Roi[] overlayRois = sliceOverlay.toArray();
		for (Roi overlayRoi : overlayRois) {
			if (resize) {
				overlayRoi = RoiScaler.scale(overlayRoi, target.getWidth()/source.getWidth(), target.getHeight()/source.getHeight(), true);
				overlayRoi.setLocation(
						(double)((target.getWidth()-overlayRoi.getBounds().width)/2), 
						(double)((target.getHeight()-overlayRoi.getBounds().height)/2));  // center current ROI
			}
			if (source.isHyperStack())
				overlayRoi.setPosition(0, targetIdx, 0);	// roi position after delete index move 1 forward
			else
				overlayRoi.setPosition(targetIdx);
			targetOverlay.add(overlayRoi);
		}
		target.setOverlay(targetOverlay);
	}
	*/
	// function to generate overlay based on ROI
	public void addRoiToOverlay(
			ImagePlus imp, // an image stack with current slice being the target image
			Roi roi
			) {
		
		//if (true) {System.out.println("debug: 1704 reached."); return;}
		
		if (imp==null || roi==null || !roi.isArea())	return;
		//int stackPosition = imp.getCurrentSlice();
		int posZ = imp.getZ();
		double[] scaleAndEdgeSize = getEdgeSizeInPixel(imp);
		// prepare new overlay, delete old overlay at current slice if exist
		Overlay newOverlay = new Overlay(); Overlay sliceOverlay = new Overlay();
		//!!! The trick to delete slice overlay !!!
		if (imp.getOverlay()!=null) {
			newOverlay = imp.getOverlay().duplicate();
			sliceOverlay = imp.getOverlay().duplicate(); 
			sliceOverlay.crop(1, imp.getNChannels(), posZ, posZ, 1, imp.getNFrames());
			Roi[] overlayRois = newOverlay.toArray();
			for (Roi overlayRoi : overlayRois) {
				//if (overlayRoi.getPosition()==stackPosition) newOverlay.remove(overlayRoi);
				if (sliceOverlay.contains(overlayRoi)) newOverlay.remove(overlayRoi);
			}
		}
		// generate All, Center and Edge ROIs
		Roi newRoiAll = (Roi) roi.clone();
		newRoiAll.setLocation(roi.getXBase(), roi.getYBase());
		if (imp.isHyperStack())
			newRoiAll.setPosition(0, posZ, 0);
		else
			newRoiAll.setPosition(posZ);
		newRoiAll.setStrokeColor(colorRoiAll);
		newRoiAll.setName("all");
		if (drawRoiAll) newOverlay.add(newRoiAll);
		
		
		/*  control ROI shape to be area, and can be shrinked by edge size
		 *	1, scale cell ROI back to original scale, using xScale and yScale:
		 * 		new width = width / xScale; new height = height / yScale;
		 *  2, shrink ROI by edge size in pixel to get center ROI:
		 *  	shrink size in pixel = shrink size in micron / pixel size
		 *  3, scale center ROI back:
		 *  	final width = center ROI width * xScale;
		 *  	final height = center ROI height * yScale;
		 * 
		 */
		//xScale: scaleFactorX = (double)imageSize/(double)imp.getWidth(); 	// current/original
		//yScale: scaleFactorY = (double)imageSize/(double)imp.getHeight();	// current/original
		//edgeOriginal: edge size in pixel in original image
		double xScale = scaleAndEdgeSize[0]; double yScale = scaleAndEdgeSize[1];
		double edgeOriginal = scaleAndEdgeSize[2];
		
		Roi oriRoiAll = RoiScaler.scale(newRoiAll, 1/xScale, 1/yScale, true);
		Roi oriRoiCenter = RoiEnlarger.enlarge(oriRoiAll, -edgeOriginal);
		Roi newRoiCenter = RoiScaler.scale(oriRoiCenter, xScale, yScale, true);
		
		if (!newRoiCenter.getTypeAsString().equals("Composite"))
			newRoiCenter = new PolygonRoi(newRoiCenter.getInterpolatedPolygon(0.5, true), Roi.FREEROI );
		newRoiCenter.enableSubPixelResolution();
		//newRoiCenter.setLocation(roi.getXBase(), roi.getYBase());
		if (imp.isHyperStack())
			newRoiCenter.setPosition(0, posZ, 0);
		else
			newRoiCenter.setPosition(posZ);
		newRoiCenter.setStrokeColor(colorRoiCenter);
		newRoiCenter.setName("center");
		
		if (drawRoiEdge) {
			Roi newRoiAllCopy = (Roi) newRoiAll.clone();
			Roi newRoiCenterCopy = (Roi) newRoiCenter.clone();
			ImagePlus roiImage = NewImage.createByteImage(
					"ROI Image", 
					newRoiAllCopy.getBounds().width, 
					newRoiAllCopy.getBounds().height, 
					1, NewImage.FILL_BLACK);
			newRoiAllCopy.setLocation(0, 0);
			newRoiCenterCopy.setLocation(
					(newRoiAllCopy.getFloatWidth()-newRoiCenterCopy.getFloatWidth())/2, 
					(newRoiAllCopy.getFloatHeight()-newRoiCenterCopy.getFloatHeight())/2);
			roiImage.setRoi(newRoiAllCopy); ImageProcessor ip = roiImage.getProcessor();
			ip.setColor(Color.WHITE); ip.fill(newRoiAllCopy);
			ip.setColor(Color.BLACK); ip.fill(newRoiCenterCopy);
			ip.setThreshold(1, 255, ImageProcessor.NO_LUT_UPDATE);
			Roi newRoiEdge = ThresholdToSelection.run(roiImage);
			roiImage.changes = false; roiImage.close(); System.gc();
			if (newRoiEdge!=null) {
				newRoiEdge.setLocation(roi.getXBase(), roi.getYBase());
				if (imp.isHyperStack())
					newRoiEdge.setPosition(0, posZ, 0);
				else
					newRoiEdge.setPosition(posZ);
				newRoiEdge.setStrokeColor(colorRoiEdge);
				newRoiEdge.setName("edge");
				newOverlay.add(newRoiEdge);
			}
		}
		if (drawRoiCenter) newOverlay.add(newRoiCenter);	// avoiding center overlay blocked by edge overlay
		
		imp.setOverlay(newOverlay);
	}
	// function to control overlay display setup
	public void overlaySetup(ImagePlus imp) {
		// first load from system prefs the saved persisted parameters
		DefaultPrefService prefs = new DefaultPrefService();
		thresholdMethod = prefs.get(String.class, "overlaySetup-thresholdMethod", thresholdMethod);
		overlayButtonAction = prefs.getInt(Integer.class, "overlaySetup-overlayButtonAction", overlayButtonAction);
		drawRoiAll = prefs.getBoolean(Boolean.class, "overlaySetup-drawRoiAll", drawRoiAll);
		colorAllIdx = prefs.getInt(Integer.class, "overlaySetup-colorAllIdx", colorAllIdx);
		drawRoiCenter = prefs.getBoolean(Boolean.class, "overlaySetup-drawRoiCenter", drawRoiCenter);
		colorCenterIdx = prefs.getInt(Integer.class, "overlaySetup-colorCenterIdx", colorCenterIdx);
		drawRoiEdge = prefs.getBoolean(Boolean.class, "overlaySetup-drawRoiEdge", drawRoiEdge);
		colorEdgeIdx = prefs.getInt(Integer.class, "overlaySetup-colorEdgeIdx", colorEdgeIdx);
		edgeSizeInMicron = prefs.getDouble(Double.class, "overlaySetup-edgeSizeInMicron", edgeSizeInMicron);
		changeAllEdgeSize = prefs.getBoolean(Boolean.class, "overlaySetup-changeAllEdgeSize", changeAllEdgeSize);
		// setup the user input dialog
		final Font highlightFont = new Font("Helvetica", Font.BOLD, 12);
		GenericDialog gd = new GenericDialog("ROI and Overlay Setup");
		String roiThresholdMessage = "Auto ROI method:";
		gd.setInsets(10,0,0);
		gd.addMessage(roiThresholdMessage, highlightFont);
		gd.addChoice("threshold:", methods, thresholdMethod);
		String overlayButtonActionMessage = "Overlay Button action:";
		gd.setInsets(10,0,0);
		gd.addMessage(overlayButtonActionMessage, highlightFont);
		String[] actions = {"Show/Hide Overlay", "ROI to Overlay", "!!!Redraw All ROI!!!"};
		gd.addChoice("action", actions, actions[overlayButtonAction]);
		String overlayDisplayMessage = "Overlay Display:";
		gd.setInsets(10,0,0);
		gd.addMessage(overlayDisplayMessage, highlightFont);
		gd.addChoice("outline:", colorStrings, colorStrings[colorAllIdx]);
		gd.addToSameRow(); gd.addCheckbox("display", drawRoiAll);
		gd.addChoice("center:", colorStrings, colorStrings[colorCenterIdx]);
		gd.addToSameRow(); gd.addCheckbox("display", drawRoiCenter);
		gd.addChoice("edge:", colorStrings, colorStrings[colorEdgeIdx]);
		gd.addToSameRow(); gd.addCheckbox("display", drawRoiEdge);
		gd.addSlider("edge size (µm):", 0, 10, edgeSizeInMicron, 0.1);
		//gd.addNumericField("edge size", edgeSizeInMicron, 1, 3, "micron");
		gd.addCheckbox("change all edge size", changeAllEdgeSize);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		
		String thresholdMethod_new = methods[gd.getNextChoiceIndex()];
		int overlayButtonAction_new = gd.getNextChoiceIndex();
		int colorAllIdx_new = gd.getNextChoiceIndex(); colorRoiAll = Colors[colorAllIdx];
		boolean drawRoiAll_new = gd.getNextBoolean();
		int colorCenterIdx_new = gd.getNextChoiceIndex(); colorRoiCenter = Colors[colorCenterIdx];
		boolean drawRoiCenter_new = gd.getNextBoolean();
		int colorEdgeIdx_new = gd.getNextChoiceIndex(); colorRoiEdge = Colors[colorEdgeIdx];
		boolean drawRoiEdge_new = gd.getNextBoolean();
		double edgeSizeInMicron_new = gd.getNextNumber();
		changeAllEdgeSize = gd.getNextBoolean();
		
		// check if item has changed, and what actions to take
		//boolean itemChanged = false;
		boolean redrawAllRoi = false;
		boolean changeOverlayDisplay = false;
		boolean redrawOverlay = changeAllEdgeSize;
		
		if (overlayButtonAction_new==2)	redrawAllRoi = true;
		if (!thresholdMethod.equals(thresholdMethod_new)) {thresholdMethod = thresholdMethod_new;}
		if (!redrawAllRoi && overlayButtonAction != overlayButtonAction_new) {overlayButtonAction = overlayButtonAction_new;}
		
		if (colorAllIdx != colorAllIdx_new) {colorAllIdx = colorAllIdx_new; changeOverlayDisplay = true;}
		if (drawRoiAll != drawRoiAll_new) {drawRoiAll = drawRoiAll_new; changeOverlayDisplay = true;}
		
		if (colorCenterIdx != colorCenterIdx_new) {colorCenterIdx = colorCenterIdx_new; changeOverlayDisplay = true;}
		if (drawRoiCenter != drawRoiCenter_new) {drawRoiCenter = drawRoiCenter_new; changeOverlayDisplay = true;}
		
		if (colorEdgeIdx != colorEdgeIdx_new) {colorEdgeIdx = colorEdgeIdx_new; changeOverlayDisplay = true;}
		if (drawRoiEdge != drawRoiEdge_new) {drawRoiEdge = drawRoiEdge_new; changeOverlayDisplay = true;}
		
		if (edgeSizeInMicron != edgeSizeInMicron_new) {edgeSizeInMicron = edgeSizeInMicron_new;}
		
		// save newly entered parameters into system prefs		
		prefs.put(String.class, "overlaySetup-thresholdMethod", thresholdMethod);
		prefs.put(Integer.class, "overlaySetup-overlayButtonAction", overlayButtonAction);
		prefs.put(Boolean.class, "overlaySetup-drawRoiAll", drawRoiAll);
		prefs.put(Integer.class, "overlaySetup-colorAllIdx", colorAllIdx);
		prefs.put(Boolean.class, "overlaySetup-drawRoiCenter", drawRoiCenter);
		prefs.put(Integer.class, "overlaySetup-colorCenterIdx", colorCenterIdx);
		prefs.put(Boolean.class, "overlaySetup-drawRoiEdge", drawRoiEdge);
		prefs.put(Integer.class, "overlaySetup-colorEdgeIdx", colorEdgeIdx);
		prefs.put(Double.class, "overlaySetup-edgeSizeInMicron", edgeSizeInMicron);
		prefs.put(Boolean.class, "overlaySetup-changeAllEdgeSize", changeAllEdgeSize);
		
		if (redrawAllRoi)
			redrawRoi(imp);
		if (changeOverlayDisplay)
			changeOverlayDisplay(imp);
		if (redrawOverlay)	// run setup function with new parameters
			redrawOverlay(imp, changeAllEdgeSize);
	
	}
	
	// function to re-draw all ROIs based on the current setup
	private void redrawRoi(ImagePlus imp) {
		Thread thread = new Thread(new Runnable() {
			public void run() {
			// this will be run in a separate thread
				
				imp.deleteRoi();
				ImagePlus impDup = imp.duplicate();
				int numZ = imp.getNSlices();
				for (int i=0; i<numZ; i++) {
					impDup.setZ(i+1);
					addRoiToOverlay(impDup, getPrincipleObjRoi(impDup));
					IJ.log("\\Update:   Re-drawing ROI: " + String.valueOf(i*100/numZ)+ "%"); 	
				}
				imp.setOverlay(impDup.getOverlay());
				impDup.changes = true; impDup.close(); System.gc();
			}});

			// start the thread
			thread.start();
	}
	// function to adjust the display of overlay
	private void changeOverlayDisplay(ImagePlus imp) {
		if (imp==null || imp.getOverlay()==null) return;
		boolean hideOverlay = imp.getHideOverlay();
		int posZ = imp.getZ();
		
		int numC = imp.getNChannels();
		int numZ = imp.getNSlices();
		int numT = imp.getNFrames();
		
		Overlay newOverlay = new Overlay();
		Color allColor = 
				new Color(Colors[colorAllIdx].getRed(), Colors[colorAllIdx].getGreen(), Colors[colorAllIdx].getBlue(), 
						drawRoiAll?255:0);
		Color centerColor = 
				new Color(Colors[colorCenterIdx].getRed(), Colors[colorCenterIdx].getGreen(), Colors[colorCenterIdx].getBlue(), 
						drawRoiCenter?255:0);
		Color edgeColor = 
				new Color(Colors[colorEdgeIdx].getRed(), Colors[colorEdgeIdx].getGreen(), Colors[colorEdgeIdx].getBlue(), 
						drawRoiEdge?255:0);
		for (int i=1; i<=numZ; i++) {
			imp.setPositionWithoutUpdate(0, i, 0);
			Overlay sliceOverlay = imp.getOverlay().duplicate();
			sliceOverlay.crop(1, numC, i, i, 1, numT);
			Roi[] rois = sliceOverlay.toArray();
			for (Roi roi : rois) {
				switch (roi.getName().toLowerCase()) {
				case "all":
					roi.setStrokeColor(allColor);
					break;
				case "center": // update edge size
					roi.setStrokeColor(centerColor);
					break;
				case "edge":
					roi.setStrokeColor(edgeColor);
					break;
				}
				if (imp.isHyperStack())
					roi.setPosition(0, i, 0);
				else
					roi.setPosition(i);
				newOverlay.add(roi);
			}
		}
		imp.setOverlay(newOverlay);
		imp.setHideOverlay(hideOverlay);
		imp.setZ(posZ);
	}
	
	// function to change overlay size
	public void redrawOverlay(ImagePlus imp, boolean changeEdge) {
		if (imp==null || imp.getOverlay()==null || !changeEdge) return;

		int numC = imp.getNChannels();
		int numZ = imp.getNSlices();
		int numT = imp.getNFrames();
		boolean hideOverlay = imp.getHideOverlay();
		int posZ = imp.getZ();
	
		for (int i=1; i<=numZ; i++) {
			imp.setPositionWithoutUpdate(0, i, 0);
			Overlay sliceOverlay = imp.getOverlay().duplicate();
			sliceOverlay.crop(1, numC, i, i, 1, numT);
			Roi[] rois = sliceOverlay.toArray();
			for (Roi roi : rois) { // should have 3 rois
				if (roi.getName().toLowerCase().equals("all")) {
					addRoiToOverlay(imp, roi);
					break;
				} else continue;
			}
		}
		imp.setHideOverlay(hideOverlay);
		imp.setZ(posZ);
	}

	
}
