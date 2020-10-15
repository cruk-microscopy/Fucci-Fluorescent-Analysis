package uk.ac.cam.cruk.fglab;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;

import ij.CompositeImage;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.GUI;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.io.OpenDialog;
import ij.plugin.CanvasResizer;
import ij.plugin.Duplicator;
import ij.plugin.MontageMaker;
import ij.plugin.PlugIn;
import ij.plugin.frame.PlugInFrame;
import ij.plugin.frame.RoiManager;
import ij.process.StackConverter;


import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.converters.ConverterUtils.DataSink;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.SerializationHelper;
import weka.classifiers.Classifier;
import weka.classifiers.trees.RandomForest;



public class MitosisTrainableClassifier implements PlugIn {

	protected static ImagePlus imp = null;
	//protected static boolean[] activeChannels;
	protected static String activeChannels = "1110";
	//protected static double probThreshold = 90;
	protected static final String[] statisticalFeatureString = {"Mean", "StdDev", "CV", "Skewness", "Kurtosis"};
	
	protected int[] positiveId;
	protected int[] negativeId;
	protected int[] unClassifiedId;
	
	protected static ArrayList<Integer> mitosisId = new ArrayList<Integer>();
	protected static ArrayList<Integer> noneMitosisId = new ArrayList<Integer>();
	protected static ArrayList<Integer> negativeMitosisId = new ArrayList<Integer>();
	
	 // temporary: positive and negative image stack for mitosis classification
	protected static ImagePlus positiveSample = null;
	protected static ImagePlus negativeSample = null;
	protected static ImagePlus predictedSample = null;
	
	protected static Classifier cls = null;
	
	protected static ArrayList<Integer> featureChannels = null;
	protected static ArrayList<Integer> statFeatures = null;	//statistical features: 
	protected static ArrayList<String> classAttributes = null;
	
	protected static boolean[] channels = {true, true, true, false};
	protected static int feature = 1;
	
	
	protected static String classLabel1 = "mitosis";
	protected static String classLabel2 = "notMitosis";
	
	protected static double probThreshold = 0.75;
	protected static boolean resultToLog = false;
	
	protected static int beginIdx = 1;
	protected static int endIdx = 0;
	//int choice = 0;
	protected static int direction = 0;
	//boolean randomize = true;
	protected static double maxSizeTestData = 100;
	protected static int maxSizeDetection = 100;
	protected static double maxTimeOut = 10;
	
	public static Boolean addDialog() {
		return true;
	}

	public static Instances createTestingInstance (
			//ImagePlus imp,
			Roi roi,
			ArrayList<String> objectClasses,
			ArrayList<Integer> channels,
			ArrayList<Integer> statisticalFeatures
			) {
		
		if (imp==null) return null;
		imp.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		imp.setRoi(roi, false);
		
		
		ArrayList<Attribute>	atts;
	    Instances			data;
	    double[]			vals;
	    
	    // 1. set up attributes
	    atts = new ArrayList<Attribute>();	    
	    for (Integer channel : channels) {
	    	String featureName = "C" + String.valueOf(channel) + "_";
	    	for (Integer statisticalFeature : statisticalFeatures) {
	    		featureName += statisticalFeatureString[statisticalFeature-1];
	    		atts.add(new Attribute(featureName));
	    	}
	    }
		atts.add(new Attribute("class", objectClasses));
	    // 2. create Instances object
	    data = new Instances("ObjectSegmentation", atts, 0);

		imp.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		imp.setRoi(roi, false);
		
		vals = new double[data.numAttributes()];
		
		int idx = 0;
	
		for (Integer c : channels) {
			imp.setC(c);
			for (Integer f : statisticalFeatures) {
				switch (f) {
					case 1:
						vals[idx] = imp.getRawStatistics().mean;
						break;
					case 2:
						vals[idx] = imp.getRawStatistics().stdDev;
						break;
					case 3:
						vals[idx] = imp.getRawStatistics().stdDev/imp.getRawStatistics().mean;
						break;
					case 4:
						vals[idx] = imp.getAllStatistics().skewness;
						break;
					case 5:
						vals[idx] = imp.getAllStatistics().kurtosis;
						break;
					default:
						continue;
				}
				idx++;
			}
		}
		
		vals[vals.length-1] = Utils.missingValue();
		data.add(new DenseInstance(1.0, vals));
		return data;
	}
	
	public static int findSimilarRoi(
			int idx,
			int searchDepth
			) {
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return -1;
		int nROI = rm.getCount();

		Roi r= rm.getRoi(idx);
		int centerX = r.getBounds().x + r.getBounds().width/2;
		int centerY = r.getBounds().y + r.getBounds().height/2;

		// search backwards
		if (idx<0) return -1;
		if (idx>0) {
			int stopIdx = Math.max(idx-searchDepth, 0);
			for (int i=idx-1; i>=stopIdx; i--) {
				if (rm.getRoi(i).contains(centerX, centerY)) {
					return i;
				}
			}
		}
		
		int stopIdx = Math.min(idx+searchDepth, nROI-1);
		for (int i=idx+1; i<stopIdx; i++) {
			if (rm.getRoi(i).contains(centerX, centerY)) {
				return i;
			}
		}
		return -1;
	}
	
	public static ArrayList<Integer> getLabelIndicesFromRoiManager(
			String label
			) {
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return null;
		int nROI = rm.getCount();
		if (nROI==0) return null;
		ArrayList<Integer> labelIndices = new ArrayList<Integer>();
		for (int i=0; i<nROI; i++) {
    		if (rm.getName(i).endsWith(label)) {
    			labelIndices.add(i);
    		}
    	}
		return labelIndices;
	}
	public static ArrayList<Integer> getLabelIndicesFromStack(
			ImagePlus imp
			) {
		if (imp==null) return null;
		ImageStack stack = imp.getStack();
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return null;
		int nROI = rm.getCount();
		if (nROI==0) return null;
		
		ArrayList<String> roiNames = new ArrayList<String>();
    	for (int i=0; i<nROI; i++) {
    		roiNames.add(rm.getName(i));
    	}
    	ArrayList<Integer> roiIndices = new ArrayList<Integer>();
    	// apply slice label to RoiManager
    	for (int i=0; i<stack.size(); i++) {
    		// get current slice label
    		String sliceLabel = stack.getSliceLabel(i+1);
    		if (sliceLabel==null) continue;
    		// get index of the label in RoiManager
    		int roiIndex = roiNames.indexOf(sliceLabel);
    		if (roiIndex==-1) {	// if index not found, guess index from label name
    			String idxString = sliceLabel.split("\\D")[0];
    			if (idxString.equals(""))	continue;
    			roiIndex = Integer.valueOf(idxString)-1; // use the first continuous digital string (-1) as index
    		}
    		if (roiIndex<0 || roiIndex>=nROI) continue;	// for wrong index, skip
    		roiIndices.add(roiIndex);
    	}
    	if (roiIndices.size()==0)	return null;
    	return roiIndices;
	}
	
	public static void applylabelToRoiManager(
			ArrayList<Integer> roiIndices,
			String label
			) {
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return;
		for (Integer roiIndex : roiIndices) {
			String roiName = rm.getName(roiIndex);
			if (roiName.endsWith(label))	continue;
			roiName = roiName.split("\\D")[0] + label;
			rm.rename(roiIndex, roiName);
		}
	}
	
	public static void removelabelFromRoiManager(
			ArrayList<Integer> protectedIndices,
			String label
			) {
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return;
		int nROI = rm.getCount();
		if (nROI==0)	return;
		for (int i=0; i<nROI; i++) {
			if (protectedIndices.contains(i))	continue;
			String roiName = rm.getName(i);
			if (!roiName.endsWith(label))	continue;
			String idxString = roiName.split("\\D")[0];
			if (idxString.equals(""))	continue;
			rm.rename(i, idxString);
		}
	}
	
	public static ImagePlus makeMontage(
			ImagePlus imp,
			int column,
			double scale
			) {
		ImagePlus montage = null;
		if (!imp.isStack())	return montage;
		int numImages = imp.getStack().getSize();
		if (numImages==1)	return montage;
		if (column<1)	return montage;
		
		ImagePlus impDup = imp.duplicate();
		ImageStack stack = impDup.getStack();
		for (int s=1; s<=stack.getSize(); s++) {
			stack.setSliceLabel(String.valueOf(s)+": ROI "+stack.getSliceLabel(s).split("\\D")[0], s);
		}
		
		//IJ.log("debug: numImages: "+String.valueOf(numImages));
		//IJ.log("debug: numImages: "+String.valueOf(column));
		//IJ.log("debug: numImages: Math.ceil(numImages/column)" + String.valueOf(Math.ceil(numImages/column)));
		int row = (int)(Math.ceil((double)(numImages)/(double)(column)));
		
		//IJ.log("debug: row: "+String.valueOf(row));
		
		MontageMaker mm2 = new MontageMaker();
		mm2.setFontSize((int) (4.5*scale));
		montage = mm2.makeMontage2(impDup, column, row, scale, 1, numImages, 1, 5, true);
		montage.setTitle(imp.getTitle()+" montage");
		impDup.changes=false;
		impDup.close();
		System.gc();
		return montage;
	}
	
	// temporary modify positive and negative mitosis sample stack
	public static void modifySamples (
			//ImagePlus imp
			) {
		if (imp==null) {
			imp = WindowManager.getCurrentImage();
		}
		if (imp==null) {
			IJ.error("No image open!");
		}
		
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) return;
		int nROI = rm.getCount();
		mitosisId.clear();
		negativeMitosisId.clear();
		noneMitosisId.clear();

		// get positive and negative samples from RoiManager
		//ArrayList<Integer> mitosisId = new ArrayList<Integer>();
		//ArrayList<Integer> noneMitosisId = new ArrayList<Integer>();
		//ArrayList<Integer> negativeMitosisId = new ArrayList<Integer>();
		
		for (int r=0; r<nROI; r++) {
			if (rm.getName(r).endsWith(classLabel1)) {
				mitosisId.add(r);
			} else if (rm.getName(r).endsWith(classLabel2)) {
				negativeMitosisId.add(r);
			} else {
				noneMitosisId.add(r);
			}
		}
		
		if (mitosisId.size()<2) {
			IJ.showMessage("label error", "Need at least 2 labeled positive samples in RoiManager. Take random entry to start GUI.");
			for (int i=mitosisId.size(); i<2; i++) {
				mitosisId.add((int) (Math.random()*nROI));
			}
		}
		if (negativeMitosisId.size()<2) {
			IJ.showMessage("label error", "Need at least 2 labeled negative samples in RoiManager. Take random entry to start GUI.");
			for (int i=negativeMitosisId.size(); i<2; i++) {
				negativeMitosisId.add((int) (Math.random()*nROI));
			}
		}
		
		//Collections.shuffle(mitosisId);
		//Collections.shuffle(noneMitosisId);
		//negativeMitosisId.addAll(noneMitosisId);
		//Collections.shuffle(negativeMitosisId);

		Roi roi = rm.getRoi(0);
		int width = roi.getBounds().width*2;
		int height = roi.getBounds().height*2;

		ImageStack stkPos = new ImageStack(width, height);
		ImageStack stkNeg = new ImageStack(width, height);
		//int positiveSampleCount = 0;

		for (Integer i : mitosisId) {
			Roi r = rm.getRoi(i);
			int centerX = r.getBounds().x + r.getBounds().width/2;
			int centerY = r.getBounds().y + r.getBounds().height/2;
			int xOff = Math.max((int)(width/2-centerX), 0);
			int yOff = Math.max((int)(height/2-centerY), 0);
			Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
			imp.setRoi(newRoi, false);
			ImagePlus imp2 = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
			imp2.setActiveChannels(activeChannels);
			new StackConverter(imp2).convertToRGB();
			imp2.setProcessor(new CanvasResizer().expandImage(imp2.getProcessor(), width, height, xOff, yOff));
			stkPos.addSlice(rm.getName(i), imp2.getProcessor());
			//positiveSampleCount++;
		}
		for (Integer i : negativeMitosisId) {
				Roi r = rm.getRoi(i);
				int centerX = r.getBounds().x + r.getBounds().width/2;
				int centerY = r.getBounds().y + r.getBounds().height/2;
				int xOff = Math.max((int)(width/2-centerX), 0);
				int yOff = Math.max((int)(height/2-centerY), 0);
				Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
				imp.setRoi(newRoi, false);
				ImagePlus imp2 = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
				imp2.setActiveChannels(activeChannels);
				new StackConverter(imp2).convertToRGB();
				imp2.setProcessor(new CanvasResizer().expandImage(imp2.getProcessor(), width, height, xOff, yOff));
				stkNeg.addSlice(rm.getName(i), imp2.getProcessor());		
		}

		// create and display positive image samples:
		positiveSample = new ImagePlus("positive samples", stkPos);
		positiveSample.show();
		
		ImageWindow positiveWindow = positiveSample.getWindow();
		ImageCanvas positiveCanvas = positiveSample.getCanvas();
		
	    int imgWidth = positiveSample.getWidth();
		int imgHeight = positiveSample.getHeight();
		double newWidth = imgWidth*10;
		double newHeight = imgHeight*10;
		positiveCanvas.setSize((int)newWidth, (int)newHeight);
		positiveCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
		
		// Adds a button to store points. 
	    Button addPositiveSample = new Button("+ Sample"); 
	    addPositiveSample.addActionListener(new ActionListener() { 
	        @Override 
	        public void actionPerformed(ActionEvent ae) { 
				int addIdx = 0;
				GenericDialog gd = new GenericDialog("add training sample");
				gd.addNumericField("ROI number", rm.getSelectedIndex()+1, 0);	// ROI index start with 0
				gd.showDialog();
				if (gd.wasCanceled()) return;
				addIdx = (int)gd.getNextNumber();
				if (addIdx<0) return;
				
				//mitosisId.add(addIdx);
				Roi r = rm.getRoi(addIdx-1);
				int centerX = r.getBounds().x + r.getBounds().width/2;
				int centerY = r.getBounds().y + r.getBounds().height/2;
				int xOff = Math.max((int)(width/2-centerX), 0);
				int yOff = Math.max((int)(height/2-centerY), 0);
				
				Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
				imp.setRoi(newRoi, false);
				ImagePlus newPositiveSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
				newPositiveSample.setActiveChannels(activeChannels);
				new StackConverter(newPositiveSample).convertToRGB();
				newPositiveSample.setProcessor(new CanvasResizer().expandImage(newPositiveSample.getProcessor(), width, height, xOff, yOff));
				ImageStack positiveSampleStack = positiveSample.getStack();
				positiveSampleStack.addSlice(rm.getName(addIdx-1), newPositiveSample.getProcessor());

				positiveSample.setStack(null, positiveSampleStack);
				positiveSample.setSlice(positiveSample.getStackSize());
				
				positiveSample.changes = true;
				positiveWindow.updateImage(positiveSample);
				newPositiveSample.close();
				positiveCanvas.setSize((int)newWidth, (int)newHeight);
				positiveCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
				positiveWindow.pack();
	        } 
	    }); 
	      
		Button deletePositiveSample = new Button("- Sample"); 
		deletePositiveSample.addActionListener(new ActionListener() { 
	        @Override 
	        public void actionPerformed(ActionEvent ae) { 

				int deleteIdx = positiveSample.getCurrentSlice();
				//IJ.log("getSlice: " + String.valueOf(sampleImage.getSlice()));
				//IJ.log("getZ: " + String.valueOf(sampleImage.getZ()));
				//IJ.log("getCurrentSlice: " + String.valueOf(sampleImage.getCurrentSlice()));
				//println(sampleImage.getStackIndex());
				//mitosisId.remove(deleteIdx);
				
				ImageStack positiveSampleStack = positiveSample.getStack();
				if (positiveSampleStack.size()==1) {
					positiveSample.changes = false;
					positiveSample.close();
					return;
				}
				
				
				
				positiveSampleStack.deleteSlice(deleteIdx);
				positiveSample.setStack(null, positiveSampleStack);
				positiveSample.setSlice(deleteIdx);
				
				positiveSample.changes = true;
				positiveWindow.updateImage(positiveSample);
				positiveCanvas.setSize((int)newWidth, (int)newHeight);
				positiveCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
				positiveWindow.pack(); 
	        } 
	    }); 
		// Adds a button to store points. 
		Button addRandomPositiveSample = new Button("Add random sample(s)"); 
		addRandomPositiveSample.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
				int numAddSamples = 0;
				GenericDialog gd = new GenericDialog("Fetch Random Sample(s)");
				gd.addNumericField("add how many random samples: ", 1, 0);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				numAddSamples = (int)gd.getNextNumber();
				if (numAddSamples<1) return;
				numAddSamples = Math.min(numAddSamples, 1000);
				//Collections.shuffle(noneMitosisId);
				int searchDepth = 2*nROI / imp.getNFrames();	// search for 4 frames center at current roi frame
				//IJ.log("debug: searchDepth : " + String.valueOf(searchDepth));
				ImageStack positiveSampleStack = positiveSample.getStack();
				ArrayList<Integer> positiveIdx = mitosisId;
				ArrayList<Integer> newPositiveSamples = new ArrayList<Integer>();
				int numSampleAdded = 0;
				
				// 1st, search neighbour roi 5s; 2nd search quick mitosis roi 10s; 3rd search random roi
				double timeOut = Math.max(numAddSamples*300, 3000);	// second time out of operation, with minimum 5 second
				double firstSearchStart = System.currentTimeMillis();	// do 5 second active search
				Collections.shuffle(positiveIdx);
				for (Integer i : positiveIdx) {	// 1st search neighbours
					if (newPositiveSamples.size()>=numAddSamples)	break;
					if (System.currentTimeMillis()-firstSearchStart>timeOut) break;
					
					int neighbourIdx = findSimilarRoi(i, searchDepth);
					
					if (neighbourIdx == -1)	continue;
					if (positiveIdx.contains(neighbourIdx))	continue;
					
					Roi r = rm.getRoi(i);
					int centerX = r.getBounds().x + r.getBounds().width/2;
					int centerY = r.getBounds().y + r.getBounds().height/2;
					if ((centerX-width/2)<=0)	continue;
					if ((centerY-height/2)<=0)	continue;
					if ((centerX+width/2)>=imp.getWidth())	continue;
					if ((centerY+height/2)>=imp.getHeight())	continue;
					
					newPositiveSamples.add(neighbourIdx);
				}
				ArrayList<Integer> allAvailableRoi = new ArrayList<Integer>();
				for (int i=0; i<nROI; i++) {
					if (newPositiveSamples.contains(i))	continue;
					if (mitosisId.contains(i))	continue;
					if (negativeMitosisId.contains(i))	continue;
					allAvailableRoi.add(i);
				}
				Collections.shuffle(allAvailableRoi);
				double[] threshold = MitosisUtility.getMitosisDetectionThreshold(imp, 1, 3, 50);
				double secondSearchStart = System.currentTimeMillis();
				for (Integer i : allAvailableRoi) {	// 2nd search quick mitosis Roi
					if (newPositiveSamples.size()>=numAddSamples)	break;
					if (System.currentTimeMillis()-secondSearchStart>timeOut) break;
					if (MitosisUtility.mitosisCheckQuick(imp, rm.getRoi(i),1,3,threshold)) {
						newPositiveSamples.add(i);
					}
				}
				allAvailableRoi.removeAll(newPositiveSamples);
				Collections.shuffle(allAvailableRoi);
				int idx=0;
				while (newPositiveSamples.size()<numAddSamples) {
					newPositiveSamples.add(allAvailableRoi.get(idx));
					idx++;
				}
				for (Integer i : newPositiveSamples) {
					Roi r = rm.getRoi(i);
	 				int centerX = r.getBounds().x + r.getBounds().width/2;
	 				int centerY = r.getBounds().y + r.getBounds().height/2;
	 				int xOff = Math.max((int)(width/2-centerX), 0);
					int yOff = Math.max((int)(height/2-centerY), 0);
					Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
					imp.setRoi(newRoi, false);
					ImagePlus newRandomSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
					newRandomSample.setActiveChannels(activeChannels);
					new StackConverter(newRandomSample).convertToRGB();
					newRandomSample.setProcessor(new CanvasResizer().expandImage(newRandomSample.getProcessor(), width, height, xOff, yOff));
					positiveSampleStack.addSlice(rm.getName(i), newRandomSample.getProcessor());
					newRandomSample.close();
					numSampleAdded++;
				}
				positiveSample.setStack(null, positiveSampleStack);
				positiveSample.setSlice(positiveSample.getStackSize());
				positiveSample.changes = true;
				positiveWindow.updateImage(positiveSample);
				positiveCanvas.setSize((int)newWidth, (int)newHeight);
				positiveCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
				positiveWindow.pack();
		    } 
		}); 
		// Adds a button to store points. 
		Button addAllPositiveSample = new Button("Add all labeled samples"); 
		addAllPositiveSample.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	
		    	ArrayList<Integer> positiveIndicesFromManager = getLabelIndicesFromRoiManager(classLabel1);
		    	if (positiveIndicesFromManager==null || positiveIndicesFromManager.size()==0)	return;
		    	ArrayList<Integer> positiveIndicesInStack = getLabelIndicesFromStack(positiveSample);
		    	if (positiveIndicesFromManager.size()-positiveIndicesInStack.size()>1000) {
		    		WaitForUserDialog wd = new WaitForUserDialog("Warning - large label size to disply", 
		    				"Too many labeld samples!\nIt could take long time to import.\nConfirm by pressing OK.");
					wd.show();
					if (wd.escPressed())	return;
		    	}
		    	
		    	ImageStack positiveSampleStack = positiveSample.getStack();
		    	for (Integer positiveIdx : positiveIndicesFromManager) {
		    		if (positiveIndicesInStack.contains(positiveIdx))	continue;
		    		
					Roi r = rm.getRoi(positiveIdx);
					
					int centerX = r.getBounds().x + r.getBounds().width/2;
					int centerY = r.getBounds().y + r.getBounds().height/2;
					int xOff = Math.max((int)(width/2-centerX), 0);
					int yOff = Math.max((int)(height/2-centerY), 0);
					
					Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
					imp.setRoi(newRoi, false);
					ImagePlus newPositiveSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
					newPositiveSample.setActiveChannels(activeChannels);
					new StackConverter(newPositiveSample).convertToRGB();
					newPositiveSample.setProcessor(new CanvasResizer().expandImage(newPositiveSample.getProcessor(), width, height, xOff, yOff));
					positiveSampleStack.addSlice(rm.getName(positiveIdx), newPositiveSample.getProcessor());
					newPositiveSample.close();
		    	}
		    	positiveSample.setStack(null, positiveSampleStack);
				positiveSample.setSlice(positiveSample.getStackSize());
				positiveSample.changes = true;
				positiveWindow.updateImage(positiveSample);
				positiveCanvas.setSize((int)newWidth, (int)newHeight);
				positiveCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
				positiveWindow.pack();
		    } 
		}); 
		// Adds a button to make montage. 
		Button makePositiveMontage = new Button("Make montage image"); 
		makePositiveMontage.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) {
		    	int column = 10;
		    	double scale = 5;
				GenericDialog gd = new GenericDialog("Make Montage Images");
				gd.addMessage("sample image number: " + String.valueOf(positiveSample.getStack().getSize()));
				gd.addNumericField("Column: ", column, 0);
				gd.addSlider("Zoom: ", 100, 1000, scale*100, 50);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				column = (int)gd.getNextNumber();
				if (column<1)	return;
				scale = gd.getNextNumber()/100;
				//int row = (int) (Math.ceil(positiveSample.getStack().getSize()/column));
		    	ImagePlus posMontage = makeMontage(positiveSample, column, scale);
		    	if (posMontage!=null)	posMontage.show();
		    } 
		}); 
		// Adds a button to store points. 
		Button applyPositiveSampleLabel = new Button("Apply labels to ROI Manager"); 
		applyPositiveSampleLabel.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	ArrayList<Integer> roiIndices = getLabelIndicesFromStack(positiveSample);
		    	applylabelToRoiManager(roiIndices, "-"+classLabel1);
		    } 
		}); 
		Button removePositiveSampleLabel = new Button("Remove labels of undisplayed"); 
		removePositiveSampleLabel.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	ArrayList<Integer> roiIndices = getLabelIndicesFromStack(positiveSample);
		    	removelabelFromRoiManager(roiIndices, classLabel1);
		    } 
		}); 
		
	    // Adds button. 
	    positiveWindow.add(addPositiveSample); 
	    positiveWindow.add(deletePositiveSample); 
	    positiveWindow.add(addRandomPositiveSample);
	    positiveWindow.add(addAllPositiveSample);
	    positiveWindow.add(makePositiveMontage);
	    positiveWindow.add(applyPositiveSampleLabel);
	    positiveWindow.add(removePositiveSampleLabel);
	    positiveWindow.pack();
	    
	    
	 // create and display negative image samples:
 		negativeSample = new ImagePlus("negative samples", stkNeg);
 		negativeSample.show();
 		
 		ImageWindow negativeWindow = negativeSample.getWindow();
 		ImageCanvas negativeCanvas = negativeSample.getCanvas();
 		
 		negativeCanvas.setSize((int)newWidth, (int)newHeight);
 		negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
 		
 		// Adds a button to store points. 
 	    Button addNegativeSample = new Button("+ Sample"); 
 	    addNegativeSample.addActionListener(new ActionListener() { 
 	        @Override 
 	        public void actionPerformed(ActionEvent ae) { 
 				int addIdx = 0;
 				GenericDialog gd = new GenericDialog("add training sample");
 				gd.addNumericField("ROI number", rm.getSelectedIndex()+1, 0);
 				gd.showDialog();
 				if (gd.wasCanceled()) return;
 				addIdx = (int)gd.getNextNumber();
 				if (addIdx<0) return;
 				
 				Roi r = rm.getRoi(addIdx-1);
 				int centerX = r.getBounds().x + r.getBounds().width/2;
 				int centerY = r.getBounds().y + r.getBounds().height/2;
 				int xOff = Math.max((int)(width/2-centerX), 0);
				int yOff = Math.max((int)(height/2-centerY), 0);
				
 				Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
 				imp.setRoi(newRoi, false);
 				ImagePlus newNegativeSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
 				newNegativeSample.setActiveChannels(activeChannels);
 				new StackConverter(newNegativeSample).convertToRGB();
 				newNegativeSample.setProcessor(new CanvasResizer().expandImage(newNegativeSample.getProcessor(), width, height, xOff, yOff));
 				ImageStack negativeSampleStack = negativeSample.getStack();
 				negativeSampleStack.addSlice(rm.getName(addIdx-1), newNegativeSample.getProcessor());

 				negativeSample.setStack(null, negativeSampleStack);
 				negativeSample.setSlice(negativeSample.getStackSize());
 				
 				negativeSample.changes = true;
 				negativeWindow.updateImage(negativeSample);
 				newNegativeSample.close();
 				negativeCanvas.setSize((int)newWidth, (int)newHeight);
 				negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
 				negativeWindow.pack();
 	        } 
 	    }); 
 		Button deleteNegativeSample = new Button("- Sample"); 
 	    deleteNegativeSample.addActionListener(new ActionListener() { 
 	        @Override 
 	        public void actionPerformed(ActionEvent ae) { 

 				int deleteIdx = negativeSample.getCurrentSlice();
 				//IJ.log("getSlice: " + String.valueOf(sampleImage.getSlice()));
 				//IJ.log("getZ: " + String.valueOf(sampleImage.getZ()));
 				//IJ.log("getCurrentSlice: " + String.valueOf(sampleImage.getCurrentSlice()));
 				//println(sampleImage.getStackIndex());
 				ImageStack negativeSampleStack = negativeSample.getStack();
 				if (negativeSampleStack.size()==1) {
 					negativeSample.changes = false;
 					negativeSample.close();
 					return;
 				}
 				negativeSampleStack.deleteSlice(deleteIdx);
 				negativeSample.setStack(null, negativeSampleStack);
 				negativeSample.setSlice(deleteIdx);
 				
 				negativeSample.changes = true;
 				negativeWindow.updateImage(negativeSample);
 				negativeCanvas.setSize((int)newWidth, (int)newHeight);
 				negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
 				negativeWindow.pack(); 
 	        } 
 	    }); 
	 	// Adds a button to store points. 
	    Button addRandomNegativeSample = new Button("Add random sample(s)"); 
	    addRandomNegativeSample.addActionListener(new ActionListener() { 
	        @Override 
	        public void actionPerformed(ActionEvent ae) { 
				int numAddSamples = 0;
				GenericDialog gd = new GenericDialog("Fetch Random Sample(s)");
				gd.addNumericField("Fetch how many random samples: ", 1, 0);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				numAddSamples = (int)gd.getNextNumber();
				if (numAddSamples<1) return;
				numAddSamples = Math.min(numAddSamples, 1000);
				//Collections.shuffle(noneMitosisId);
				ImageStack negativeSampleStack = negativeSample.getStack();
				ArrayList<Integer> negativeIdx = negativeMitosisId;
				int numSampleAdded = 0;
				while (numSampleAdded < numAddSamples) {
					
					int randIdx = (int) (Math.random()*nROI);
					String roiName = rm.getName(randIdx);
					if (negativeIdx.contains(randIdx))	continue;
					if (roiName.endsWith(classLabel1) || roiName.endsWith(classLabel2))	continue;
					
					Roi r = rm.getRoi(randIdx);
					
					int centerX = r.getBounds().x + r.getBounds().width/2;
					int centerY = r.getBounds().y + r.getBounds().height/2;
					
					if ((centerX-width/2)<=0)	continue;
					if ((centerY-height/2)<=0)	continue;
					if ((centerX+width/2)>=imp.getWidth())	continue;
					if ((centerY+height/2)>=imp.getHeight())	continue;
				
					Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
					imp.setRoi(newRoi, false);
					ImagePlus newRandomSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
					newRandomSample.setActiveChannels(activeChannels);
					new StackConverter(newRandomSample).convertToRGB();
					negativeSampleStack.addSlice(roiName, newRandomSample.getProcessor());
					newRandomSample.close();
					negativeIdx.add(randIdx);
					numSampleAdded++;
				}
				negativeSample.setStack(null, negativeSampleStack);
 				negativeSample.setSlice(negativeSample.getStackSize());
 				negativeSample.changes = true;
 				negativeWindow.updateImage(negativeSample);
				negativeCanvas.setSize((int)newWidth, (int)newHeight);
 				negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
 				negativeWindow.pack();
	        } 
	    }); 
	    // Adds a button to store points. 
 		Button addAllNegativeSample = new Button("Add all labeled samples"); 
 		addAllNegativeSample.addActionListener(new ActionListener() { 
 		    @Override 
 		    public void actionPerformed(ActionEvent ae) { 
 		    	
 		    	ArrayList<Integer> negativeIndicesFromManager = getLabelIndicesFromRoiManager(classLabel2);
 		    	if (negativeIndicesFromManager==null || negativeIndicesFromManager.size()==0)	return;
 		    	ArrayList<Integer> negativeIndicesInStack = getLabelIndicesFromStack(negativeSample);
 		    	if (negativeIndicesFromManager.size()-negativeIndicesInStack.size()>1000) {
 		    		WaitForUserDialog wd = new WaitForUserDialog("Warning - large label size to disply", 
 		    				"Too many labeld samples!\nIt could take long time to import.\nConfirm by pressing OK.");
 					wd.show();
 					if (wd.escPressed())	return;
 		    	}
 		    	
 		    	ImageStack negativeSampleStack = negativeSample.getStack();
 		    	for (Integer negativeIdx : negativeIndicesFromManager) {
 		    		if (negativeIndicesInStack.contains(negativeIdx))	continue;
 		    		
 					Roi r = rm.getRoi(negativeIdx);
 					
 					int centerX = r.getBounds().x + r.getBounds().width/2;
 					int centerY = r.getBounds().y + r.getBounds().height/2;
 					int xOff = Math.max((int)(width/2-centerX), 0);
 					int yOff = Math.max((int)(height/2-centerY), 0);
 					
 					Roi newRoi = new Roi((int)(centerX-width/2), (int)(centerY-height/2), width, height);
 					imp.setRoi(newRoi, false);
 					ImagePlus newNegativeSample = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
 					newNegativeSample.setActiveChannels(activeChannels);
 					new StackConverter(newNegativeSample).convertToRGB();
 					newNegativeSample.setProcessor(new CanvasResizer().expandImage(newNegativeSample.getProcessor(), width, height, xOff, yOff));
 					negativeSampleStack.addSlice(rm.getName(negativeIdx), newNegativeSample.getProcessor());
 					newNegativeSample.close();
 		    	}
 		    	negativeSample.setStack(null, negativeSampleStack);
 		    	negativeSample.setSlice(negativeSample.getStackSize());
 		    	negativeSample.changes = true;
 				negativeWindow.updateImage(negativeSample);
 				negativeCanvas.setSize((int)newWidth, (int)newHeight);
 				negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
 				negativeWindow.pack();
 		    } 
 		}); 
 	// Adds a button to make montage. 
	Button makeNegativeMontage = new Button("Make montage image"); 
	makeNegativeMontage.addActionListener(new ActionListener() { 
	    @Override 
	    public void actionPerformed(ActionEvent ae) {
	    	int column = 10;
	    	double scale = 5;
			GenericDialog gd = new GenericDialog("Make Montage Images");
			gd.addMessage("sample image number: " + String.valueOf(negativeSample.getStack().getSize()));
			gd.addNumericField("Column: ", column, 0);
			gd.addSlider("Zoom: ", 100, 1000, scale*100, 50);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			column = (int)gd.getNextNumber();
			if (column<1)	return;
			scale = gd.getNextNumber()/100;
			//int row = (int) (Math.ceil(negativeSample.getStack().getSize()/column));
	    	ImagePlus negMontage = makeMontage(negativeSample, column, scale);
	    	if (negMontage!=null)	negMontage.show();
	    } 
	}); 
	    // Adds a button to store points. 
 		Button applyNegativeSampleLabel = new Button("Apply labels to ROI Manager"); 
 		applyNegativeSampleLabel.addActionListener(new ActionListener() { 
 		    @Override 
 		    public void actionPerformed(ActionEvent ae) { 
 		    	ArrayList<Integer> roiIndices = getLabelIndicesFromStack(negativeSample);
 		    	applylabelToRoiManager(roiIndices, "-"+classLabel2);
 		    } 
 		}); 
 		Button removeNegativeSampleLabel = new Button("Remove labels of undisplayed"); 
 		removeNegativeSampleLabel.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	ArrayList<Integer> roiIndices = getLabelIndicesFromStack(negativeSample);
		    	removelabelFromRoiManager(roiIndices, classLabel2);
		    } 
		}); 
 	    // Adds button. 
 	    negativeWindow.add(addNegativeSample); 
 	    negativeWindow.add(deleteNegativeSample); 
 	    negativeWindow.add(addRandomNegativeSample);
 	    negativeWindow.add(addAllNegativeSample);
 	    negativeWindow.add(makeNegativeMontage);
 	    negativeWindow.add(applyNegativeSampleLabel);
 	    negativeWindow.add(removeNegativeSampleLabel);
 	    negativeWindow.pack();
	}
	
	public static String getImageInfo (
			ImagePlus imp) {
		if (imp==null)	return ("No image recognized!");
		String imgTitle = "Image: " + imp.getTitle();
		String imgSzie = " size: "
				+ new DecimalFormat(".#").format(imp.getSizeInBytes()/1048576)
				+ "MB (" + String.valueOf(imp.getBitDepth()) + " bit)";
		int[] dim = imp.getDimensions();
		String imgDimension = " X:" + String.valueOf(dim[0])
						   + ", Y:" + String.valueOf(dim[1])
						   + ", Z:" + String.valueOf(dim[3])
						   + ", C:" + String.valueOf(dim[2])
						   + ", T:" + String.valueOf(dim[4]);
		return (imgTitle + "\n" + imgSzie + "\n" + imgDimension);
	}
	
	public static String getRoiManagerInfo () {
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) {
			return ("ROI Manager is not open!");
		}
		int nROI = rm.getCount();
		
		String roiManagerInfo = String.valueOf(nROI) + " ROIs in Manager.";
		
		boolean indexAllMatch = true;
		ArrayList<Integer> unmatchIdx = new ArrayList<Integer>();
		ArrayList<String> labels = new ArrayList<String>();
		ArrayList<Integer> labelCounts = new ArrayList<Integer>();
		String roiName = ""; String roiIndex = "";
		String[] labelParts = null;
		for (int i=0; i<nROI; i++) {
			// we take the first digit series as ROI index
			roiName = rm.getName(i);
			roiIndex = roiName;
			while (roiIndex.length()!=0 && !Character.isDigit(roiIndex.charAt(0))) {
				roiIndex = roiIndex.substring(1, roiIndex.length());
			}
			roiIndex = roiIndex.split("\\D")[0];
			if (Integer.valueOf(roiIndex)!=(i+1)) {
				indexAllMatch = false;
				unmatchIdx.add(i);
			}
			
			labelParts = roiName.split("-");
			if (labelParts.length>1) {
				if (!labels.contains(labelParts[1])) {
					labels.add(labelParts[1]);
					labelCounts.add(1);
				} else {
					int idx = labels.indexOf(labelParts[1]);
					labelCounts.set(idx, labelCounts.get(idx)+1);
				}
			}
		}

		
		String indexMatch = indexAllMatch?"all ROI name match with index.":"ROI name and index not match (check log).";
		if (!indexAllMatch) {
			IJ.log("Unmatched ROI: ");
			for (Integer idx : unmatchIdx) {
				IJ.log("   ROI: " + String.valueOf(idx+1) + " with name: " + rm.getName(idx));
			}
		}
		/* inactive: use -mitosis pattern for label ROIs in RoiManager
		String roiLabel = "ROI labels: ";
		for (int i=0; i<labels.size(); i++) {
			roiLabel += labels.get(i) + " (" + String.valueOf(labelCounts.get(i))+"),  ";
		}
		roiLabel = roiLabel.substring(0, roiLabel.length()-2);
		return (roiManagerInfo + "\n" + indexMatch + "\n" + roiLabel);
		*/
		return (roiManagerInfo + "\n" + indexMatch);

	}
	public static void addSamplePanel (
			Frame f
			) throws Exception {
		
		RoiManager rm = RoiManager.getInstance2();
		// configure and create the "Sample image and ROIs" panel
		Label sampleTitle = new Label("Sample Image and ROIs:");
		sampleTitle.setFont(new Font("Helvetica", Font.BOLD, 12));
		sampleTitle.setMaximumSize(new Dimension(500, 10));
		f.add(sampleTitle);
		
		
		
		JPanel samplePanel = new JPanel();
		samplePanel.setLayout(new BoxLayout (samplePanel, BoxLayout.Y_AXIS));
		//samplePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		samplePanel.setBorder(new EmptyBorder(new Insets(10, 10, 10, 10)));
		samplePanel.setBackground(f.getBackground());
		samplePanel.setMaximumSize(new Dimension(500, 200));
		
		JTextArea imageInfo = new JTextArea(50, 8); 
		imageInfo.setEditable(false);
		imageInfo.setText(getImageInfo(imp));
		if (imp==null) {
			imageInfo.setFont(new Font("Helvetica", Font.BOLD, 12));
		}
		
		
		JTextArea roiInfo = new JTextArea(50, 8); 
		roiInfo.setEditable(false);
		roiInfo.setText(getRoiManagerInfo());
		if (rm==null) {
			roiInfo.setFont(new Font("Helvetica", Font.BOLD, 12));
		}
		
		JButton refresh = new JButton("refresh source");
		refresh.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	ImagePlus activeImage = WindowManager.getCurrentImage();
		    	if (activeImage==positiveSample ||
	    			activeImage==negativeSample ||
					activeImage==predictedSample ||  
					activeImage.getTitle().toLowerCase().contains("montage")
	    			) {
		    		// do nothing
		    	} else {
		    		imp = activeImage;
		    	}
		    	/*
		    	if (WindowManager.getCurrentImage()==negativeSample)	return;
		    	if (WindowManager.getCurrentImage()==predictedSample)	return;
		    	if (WindowManager.getCurrentImage().getTitle().toLowerCase().contains("montage"))	return;
		    	*/
		    	RoiManager rm = RoiManager.getInstance2();
		    	imageInfo.setText(getImageInfo(imp));
		    	if (imp==null) {
					imageInfo.setFont(new Font("Helvetica", Font.BOLD, 12));
				}
		    	roiInfo.setText(getRoiManagerInfo());
		    	if (rm==null) {
					roiInfo.setFont(new Font("Helvetica", Font.BOLD, 12));
				}
		    }
		});
		refresh.setPreferredSize(new Dimension(40, 40));
		imageInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
		roiInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
		refresh.setAlignmentX(Component.LEFT_ALIGNMENT);
		samplePanel.add(imageInfo);
		samplePanel.add(roiInfo);
		samplePanel.add(refresh);
		f.add(sampleTitle);
		f.add(samplePanel);
		//f.add(refresh);
	}
	public static void addClassificationPanel (
			Frame f
			//ImagePlus imp
			) throws Exception {
		
		//JPanel classifierPanel = new JPanel();
		//classifierPanel.setLayout(new BoxLayout (classifierPanel, BoxLayout.Y_AXIS));
		//classifierPanel.setBorder(new EmptyBorder(new Insets(10, 15, 10, 100)));
		//classifierPanel.setBackground(f.getBackground());
		cls = null;
		
		Label classifierTitle = new Label("Classification:");
		classifierTitle.setFont(new Font("Helvetica", Font.BOLD, 12));
		classifierTitle.setMaximumSize(new Dimension(500, 10));
		f.add(classifierTitle);
		
		JPanel classifierPanel = new JPanel();
		classifierPanel.setLayout(new GridLayout(7, 1));
		//classifierButtonPanel.setMaximumSize(new Dimension(120, 100));
		classifierPanel.setBorder(new EmptyBorder(new Insets(10, 55, 10, 10)));
		classifierPanel.setBackground(f.getBackground());
		
		JTextArea classifierInfo = new JTextArea(30, 8); 
		classifierInfo.setEditable(false);
		
		
		
		Button checkClassifier = new Button("check classifier");
		checkClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
				if (cls==null)	{
					classifierInfo.setText("No classifier.");
					return;
				}
				try {
					featureChannels = WekaUtility.getFeatureChannelsFromModel(cls);
					String channelInfo = "Channels: ";
					for (Integer c : featureChannels) {
						channelInfo += String.valueOf(c) + " ";
					}
					statFeatures = WekaUtility.getObjFeatureFromModel(cls);
					String featureInfo = "Object feature: ";
					
					if (statFeatures.size()==5) {
						featureInfo += "advanced";
					} else if (statFeatures.size()==3) {
						featureInfo += "standard";
					} else if (statFeatures.size()==2) {
						featureInfo += "simple";
					}
					
					
					//for (Integer f : featureChannels) {
					//	featureInfo += String.valueOf(f) + " ";
					//}
					
			    	classAttributes = WekaUtility.getClassAttributeFromModel(cls);
			    	String classInfo = "Class: ";
			    	for (String a : classAttributes) {
			    		classInfo += a + ", ";
			    	}
			    	classInfo = classInfo.substring(0, classInfo.length()-2);
			    	/*
			    	int numInstance = WekaUtility.getNumInstanceFromModel(cls);
			    	String instanceInfo = "training sample: " + String.valueOf(numInstance);
			    	*/
			    	classifierInfo.setText(channelInfo + "\n" + featureInfo + "\n" + classInfo);
			    	
				} catch (Exception e) {
					// TODO Auto-generated catch block
					classifierInfo.setText("Failed to check information of classifier.");
					e.printStackTrace();
				}
		    } 
		});
		
		Button loadClassifier = new Button("load classifier");
		loadClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	
		    	String modelPath = null;
		    	JFileChooser jfc = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());

				int returnValue = jfc.showOpenDialog(null);
				// int returnValue = jfc.showSaveDialog(null);

				if (returnValue == JFileChooser.APPROVE_OPTION) {
					File selectedFile = jfc.getSelectedFile();
					modelPath = selectedFile.getAbsolutePath();
				}
		    
				/*
		    	OpenDialog od = new OpenDialog("Load classifier");
				String modelPath = od.getPath();
				*/
				if (modelPath==null)	{
					return;
				}
				
				try {
					cls = (Classifier) SerializationHelper.read(modelPath);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					classifierInfo.setText("Failed to load classifier.");
					e.printStackTrace();
				}
		    } 
		});
		
		Button applyClassifier = new Button("apply classifier");
		applyClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	if (cls==null) {
		    		classifierInfo.setText("No classifier found!\nLoad or train a classifier first.");
		    		return;
		    	}
		    	if (imp==null)	return;
		    	RoiManager rm = RoiManager.getInstance2();
		    	if (rm==null)	return;
		    	int nROI = rm.getCount();
		    	if (nROI==0)	return;
		    	if (endIdx==0)	endIdx=nROI;
		    	// get testing samples
		    	ArrayList<Integer> testSampleIdx = new ArrayList<Integer>();
		    	for (int i=0; i<nROI; i++) {
		    		testSampleIdx.add(i);
		    	}
		    	//IJ.log("debug: testSampleIdx.size(): " + String.valueOf(testSampleIdx.size()));
		    	//boolean doAll = false;
		    	//int beginIdx = 1;
		    	//int endIdx = nROI;
		    	//int choice = 0;
		    	//int direction = 0;
		    	//boolean randomize = true;
		    	//double maxSizeTestData = 100;
		    	//int maxSizeDetection = 100;
		    	//double maxTimeOut = 10;
		    	
		    	if (nROI>3000) {
		    		final Font highlightFont = new Font("Helvetica", Font.BOLD, 12);
			    	GenericDialog gd = new GenericDialog("Classify data (ROI):");
			    	
			    	String dataSelectionMessage = "Data Selection:";
					gd.setInsets(10,0,0);
					gd.addMessage(dataSelectionMessage, highlightFont);
					//gd.setInsets(10,-50,0);\
					gd.setInsets(0,0,5);
					gd.addNumericField("select range: from", beginIdx, 0, 6, "");
			    	//gd.addSlider("select range of data, from", 1, nROI, beginIdx, 1);
			    	//gd.setInsets(10,-50,0);
					//gd.setInsets(0,0,0);
					gd.addNumericField("select range:      to", endIdx, 0, 6, "");
			    	//gd.addSlider("select range of data, to", 1, nROI, endIdx, 1);
			    	//String[] items = {"on subset of data", "until number of detections", "for fix length of time"};
			    	//gd.addChoice("stop criterion", items, items[choice]);
					gd.setInsets(0,0,0);
			    	String[] directions = {"forward", "backward", "randomize"};
			    	gd.addChoice("operation direction", directions, directions[direction]);
			    	gd.addSlider("probability threshold (%)", 50, 100, probThreshold*100);
			    	
			    	String stopMessage = "Stopping Criterion:";
					gd.setInsets(10,0,0);
					gd.addMessage(stopMessage, highlightFont);
					
			    	gd.addSlider("after (%) of selected data classified", 0.1, 100, maxSizeTestData, 0.1);	// maximum data operation fraction
			    	gd.addSlider("after positive detections", 1, 2000, maxSizeDetection, 1);	 // maximum positive detection 1000
			    	gd.addSlider("after minutes", 0.5, 240, maxTimeOut, 0.5);	// maximum timeout is 4 hours
					gd.showDialog();
					if (gd.wasCanceled()) return;
					
					beginIdx = (int) gd.getNextNumber();
					endIdx = (int) gd.getNextNumber();
					
					//beginIdx = Math.max(1, beginIdx);	beginIdx = Math.min(nROI, beginIdx);
					//endIdx = Math.min(nROI, endIdx);	endIdx = Math.max(1, endIdx);
					if (beginIdx<1 || endIdx>nROI || endIdx<beginIdx+1) {
						IJ.error("range selection wrong!");
						return;
					}
					//endIdx = Math.max(beginIdx+1, endIdx);
					//endIdx = endIdx<=beginIdx?beginIdx+1:endIdx;
					
					//choice = gd.getNextChoiceIndex();
					direction = gd.getNextChoiceIndex();
					probThreshold = gd.getNextNumber()/100;

					maxSizeTestData = gd.getNextNumber();
					maxSizeDetection = (int) gd.getNextNumber();
					maxTimeOut = gd.getNextNumber();
		    	} 
		    	

	    		testSampleIdx = new ArrayList<Integer>(testSampleIdx.subList(beginIdx, endIdx));
	    		
	    		//allWords = Arrays.asList(strTemp.toLowerCase().split("\\s+"));
	    		//allWords.addAll(Arrays.asList(strTemp.toLowerCase().split("\\s+")));
	    		
	    		//testSampleIdx = (ArrayList<Integer>) testSampleIdx.subList(0, sizeTestData);
	    		//IJ.log("debug after dialog: testSampleIdx.size(): " + String.valueOf(testSampleIdx.size()));
	    		switch (direction) {
	    		case 1:	// reverse order
	    			Collections.reverse(testSampleIdx);
	    		case 2:	// random order
	    			Collections.shuffle(testSampleIdx);
	    		}
	    		testSampleIdx = new ArrayList<Integer>(testSampleIdx.subList(0, (int)(maxSizeTestData*testSampleIdx.size()/100)));
	    		//IJ.log("debug after fraction of data: testSampleIdx.size(): " + String.valueOf(testSampleIdx.size()));
	    		
	    			//testSampleIdx = (ArrayList<Integer>) testSampleIdx.subList(0, sizeTestData);
	    			//IJ.log("debug: maxSizeTestData" + String.valueOf(maxSizeTestData));
	    			//IJ.log("debug: maxSizeDetection.size()" + String.valueOf(maxSizeDetection));
	    			//IJ.log("debug: maxTimeOut.get(0)" + String.valueOf(maxTimeOut));
		    		
		    	
				
		    	
		    	
		    	ArrayList<Integer> positiveLabelIndices = new ArrayList<Integer>(); // avoid null type overlapping for Ambigouous method "addAll"
		    	ArrayList<Integer> negativeLabelIndices = new ArrayList<Integer>();
		    	ArrayList<Integer> predictedLabelIndices = new ArrayList<Integer>();
		    	ArrayList<Double>	predictedLabelProbility = new ArrayList<Double>();
		    	positiveLabelIndices = getLabelIndicesFromStack(positiveSample);
		    	negativeLabelIndices = getLabelIndicesFromStack(negativeSample);
		    			
		    	classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	
		    	String trainingInfo = "Applying classifier to selected ROIs...\n "
		    			+ "It could take roughly " + String.valueOf(nROI/600) + " minutes.\n"
		    			+ "Wait and be patient. Don't change anything before it finishes please.";
		    	classifierInfo.setText(trainingInfo);
		    	
				try {
					featureChannels = WekaUtility.getFeatureChannelsFromModel(cls);
					statFeatures = WekaUtility.getObjFeatureFromModel(cls);
			    	classAttributes = WekaUtility.getClassAttributeFromModel(cls);
				} catch (Exception e2) {
					// TODO Auto-generated catch block
					e2.printStackTrace();
					return;
				}
		    	
				// Future work: implement progress bar
				int nTestData = testSampleIdx.size();
				int idx = 0;
			    int nRoiPerSecond = 0;
			    double percentageDone = 0;
			    String percentageInfo = String.valueOf(percentageDone/10) + "% done." + " (" + String.valueOf(nRoiPerSecond) + " ROIs/second)";
			    String timeLeftInfo  = "Estimated time remaining: ";
			    double loopStart = System.currentTimeMillis();
			    int RoiAtCurrentSecond = 0;
			    int nowInSecond = 0;
			    int lastSecond = 0;
			    double estSecondLeft = 0;
			    double estMinuteLeft = 0;
			    Roi oriRoi = imp.getRoi();	imp.deleteRoi();
			    ImagePlus impTest = imp.duplicate();
			    imp.setRoi(oriRoi);
			    ImagePlus impCrop = new ImagePlus();
			    Roi roi = null;	Roi croppedRoi = null;
			    
		    	for (Integer i : testSampleIdx) {
		    		
		    		//impCrop.flush();
		    		// get progress info
		    		idx++;
		    		lastSecond = nowInSecond;
		    		nowInSecond = (int)((System.currentTimeMillis()-loopStart)/1000);
		    		
		    		

	    			if (nowInSecond>maxTimeOut*60) {
	    				break;
	    			}

		    		trainingInfo = "Classifying ROI: " + String.valueOf(idx) + " (of in total " + String.valueOf(nTestData) + ")";
		    		if (nowInSecond!=lastSecond) {
		    			//nRoiPerSecond = idx-RoiAtCurrentSecond;
		    			nRoiPerSecond = idx/nowInSecond;
		    			//RoiAtCurrentSecond = idx;
		    		}
		    		percentageDone = idx*1000/nTestData;
	    			percentageInfo = String.valueOf(percentageDone/10) + "% done." + " ("+String.valueOf(nRoiPerSecond)+" ROIs/second)";
	    			if (nRoiPerSecond!=0) {
	    				estSecondLeft = (nTestData-idx)*10/nRoiPerSecond;
	    				if (estSecondLeft>600) {
	    					estMinuteLeft = Math.round(estSecondLeft/60);
	    					timeLeftInfo = "Estimated time remaining: " + String.valueOf((double)(estMinuteLeft/10))+" minutes.";
	    				} else {
	    					timeLeftInfo = "Estimated time remaining: " + String.valueOf(estSecondLeft/10)+" seconds.";
	    				}
	    				//timeLeftInfo = estSecondLeft>600 ? String.valueOf(estSecondLeft/600)+" minutes." : String.valueOf(estSecondLeft/10)+" seconds.";
	    				//timeLeftInfo =  + timeLeftInfo;
	    			}

	    			
		    		classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    		classifierInfo.setText(trainingInfo + "\n" + percentageInfo + "\n" + timeLeftInfo);
		    		
		    		// check if sample labeled
		    		if (positiveLabelIndices.contains(i) || negativeLabelIndices.contains(i)) 	continue;
		    		
		    		
		    		// apply classifier to current ROI
	    			roi = (Roi) rm.getRoi(i).clone();
	    			impTest.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
	    			impTest.setRoi(rm.getRoi(i), false);
		    		
		    		//HERE!
		    		if (activeChannels.charAt(impTest.getNChannels()-1)=='0') {
		    			impCrop = new Duplicator().run(impTest, 1, impTest.getNChannels()-1, 1, impTest.getNSlices(), impTest.getT(), impTest.getT() );
		    		} else {
		    			impCrop = new Duplicator().run(impTest, 1, impTest.getNChannels(), 1, impTest.getNSlices(), impTest.getT(), impTest.getT() );
		    		}
		    		croppedRoi = WekaUtility.cropRoi(impTest, roi);
		    		croppedRoi.setLocation(0,0);
		    		
		    		Instances dataTest = WekaUtility.createTestingInstance(impCrop, croppedRoi, classAttributes, featureChannels, statFeatures);
		    		
		    				//(rm.getRoi(i), );
		    		
		    		
		    		int classIdx=dataTest.numAttributes()-1;
		    		dataTest.setClassIndex(classIdx);
		    		double label;	double[] probs;
					try {
						label = cls.classifyInstance(dataTest.instance(0));
						probs = cls.distributionForInstance(dataTest.instance(0));
					} catch (Exception e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
						continue;
					}
					dataTest.instance(0).setClassValue(label);
				    //String roiName = rm.getName(i);

				    // get predicted result
				    if (probs[0]>probThreshold) {
				    	//if (!positiveLabelIndices.contains(i)) {
			    		predictedLabelIndices.add(i);
			    		predictedLabelProbility.add(probs[0]);
				    	//}
				    	if (resultToLog) {
				    		IJ.log("   ROI " + (i+1) + ": mitosis found with probability: " + String.valueOf(probs[0]*100) + "%");
				    	}

			    		if (predictedLabelIndices.size()>=maxSizeDetection) {
			    			break;
				    	}
				    }  
		    	}
		    	impTest.changes=false;
		    	impTest.close();
		    	impCrop.changes=false;
		    	impCrop.close();
		    	IJ.run("Collect Garbage", "");
		    	System.gc();
		    	// report result in the classifier panel
		    	classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	classifierInfo.setText("Classification complete.\nGenerating predicted ROI stack...");
		    	if (predictedLabelIndices.size()==0) {
		    		classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    		classifierInfo.setText("Classifier didn't find any new positive labels.");
		    		return;
		    	}
		    	
		    	// generate predicted positive sample stack
		    	int diameter = 0;
		    	int confirmCount = 0;	int totalCount = 0;
		    	while (confirmCount<3 && totalCount<1000) {
		    		totalCount++;
		    		int i = (int)(nROI*Math.random());
		    		if (diameter != rm.getRoi(i).getBounds().width*2) {
		    			diameter = rm.getRoi(i).getBounds().width*2;
		    			//confirmCount++;
		    		} else {
		    			confirmCount++;
		    		}
		    	}

				ImageStack stkPredict = new ImageStack(diameter, diameter);
				for (Integer i : predictedLabelIndices) {
					Roi r = rm.getRoi(i);
					int centerX = r.getBounds().x + r.getBounds().width/2;
					int centerY = r.getBounds().y + r.getBounds().height/2;
					int xOff = Math.max((int)(diameter/2-centerX), 0);
					int yOff = Math.max((int)(diameter/2-centerY), 0);
					Roi newRoi = new Roi((int)(centerX-diameter/2), (int)(centerY-diameter/2), diameter, diameter);
					imp.setRoi(newRoi, false);
					ImagePlus imp2 = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
					imp2.setActiveChannels(activeChannels);
					new StackConverter(imp2).convertToRGB();
					imp2.setProcessor(new CanvasResizer().expandImage(imp2.getProcessor(), diameter, diameter, xOff, yOff));
					stkPredict.addSlice(rm.getName(i), imp2.getProcessor());		
				}
				predictedSample = new ImagePlus("predicted positive samples", stkPredict);
				predictedSample.show();
		    	
				classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	classifierInfo.setText("Classification complete.");
				
				ImageWindow predictWindow = predictedSample.getWindow();
				ImageCanvas predictCanvas = predictedSample.getCanvas();
				
			    int imgWidth = predictedSample.getWidth();
				int imgHeight = predictedSample.getHeight();
				double newWidth = imgWidth*10;
				double newHeight = imgHeight*10;
				predictCanvas.setSize((int)newWidth, (int)newHeight);
				predictCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
				// Adds a button to store points. 
			    Button sortByProb = new Button("sort by probability"); 
			    sortByProb.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) { 
			        	int numSlices = predictedSample.getNSlices();
			        	ImageStack stack = predictedSample.getStack();
			        	//ArrayList<Double> probs = new ArrayList<Double>(predictedLabelProbility);
			        	boolean swapped = false;
			        	for (int pass=0; pass<numSlices; pass++) {
			        		for (int iSlice=1; iSlice<(numSlices-pass); iSlice++) {
			        			double comp = predictedLabelProbility.get(iSlice - 1) - predictedLabelProbility.get(iSlice);
			        			if (comp<0) {	// sort from large to small probability
			        				swapped = true;
			    					stack.addSlice(stack.getSliceLabel(iSlice), stack.getProcessor(iSlice), iSlice+1);
			    					stack.deleteSlice(iSlice);
			    					//Collections.swap(probs, iSlice-1, iSlice);
			    					Collections.swap(predictedLabelProbility, iSlice-1, iSlice);
			    					Collections.swap(predictedLabelIndices, iSlice-1, iSlice);
			        			}
			        		}
			        		if(!swapped)break;
			        	}
			        	predictedSample.setStack(null,stack);
			        	predictedSample.updateAndDraw();
		        		return;
			        } 
			    }); 
			    Button sortByIndex = new Button("sort by ROI index"); 
			    sortByIndex.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) { 
			        	int numSlices = predictedSample.getNSlices();
			        	ImageStack stack = predictedSample.getStack();
			        	boolean swapped = false;
			        	for (int pass=0; pass<numSlices; pass++) {
			        		for (int iSlice=1; iSlice<(numSlices-pass); iSlice++) {
			        			int comp = predictedLabelIndices.get(iSlice - 1) - predictedLabelIndices.get(iSlice);
			        			if (comp>0) {	// sort from small to large ROI index
			        				swapped = true;
			    					stack.addSlice(stack.getSliceLabel(iSlice), stack.getProcessor(iSlice), iSlice+1);
			    					stack.deleteSlice(iSlice);
			    					Collections.swap(predictedLabelIndices, iSlice-1, iSlice);
			    					Collections.swap(predictedLabelProbility, iSlice-1, iSlice);
			        			}
			        		}
			        		if(!swapped)break;
			        	}
			        	predictedSample.setStack(null,stack);
			        	predictedSample.updateAndDraw();
		        		return;
			        } 
			    }); 
			     
			    Button addToPositive = new Button("add to positive training sample"); 
			    addToPositive.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) {
			        	// use dialog for user input
			        	int currentIdx = predictedSample.getCurrentSlice();
			        	int numSlices = predictedSample.getNSlices();
			        	
			        	GenericDialog gd = new GenericDialog("Indicate Range:");
			        	gd.addSlider("From: ", 1, numSlices, currentIdx, 1);
			        	gd.addSlider("To: ", 1, numSlices, currentIdx, 1);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						int beginIdx = (int)gd.getNextNumber();
						int endIdx = (int)gd.getNextNumber();
						if (endIdx<beginIdx) return;
						
						ImageStack predictedStack = predictedSample.getStack();
						ImageStack positiveSampleStack = positiveSample.getStack();
						for (int idx=beginIdx; idx<=endIdx; idx++) {
							positiveSampleStack.addSlice(predictedStack.getSliceLabel(idx), predictedStack.getProcessor(idx));
			 				positiveSample.setStack(null, positiveSampleStack);
			 				positiveSample.setSlice(positiveSample.getStackSize());
			 				positiveSample.changes = true;	
						}
						if (endIdx==numSlices && beginIdx==1) {
							predictedSample.changes=false;
							predictedSample.close();
							return;
						}	
						for (int idx=endIdx; idx>=beginIdx; idx--) {
							predictedStack.deleteSlice(idx);
							predictedLabelIndices.remove(idx-1);
							predictedLabelProbility.remove(idx-1);
						}
						predictedSample.setStack(null, predictedStack);
						//predictedSample.setSlice(idx);
						predictedSample.changes = true;
						//negativeWindow.updateImage(negativeSample);
		 				//negativeCanvas.setSize((int)newWidth, (int)newHeight);
		 				//negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
		 				//negativeWindow.pack();
			        } 
			    }); 
			    Button addToNegative = new Button("add to negative training sample"); 
			    addToNegative.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) { 
			        	// use dialog for user input
			        	int currentIdx = predictedSample.getCurrentSlice();
			        	int numSlices = predictedSample.getNSlices();
			        	
			        	GenericDialog gd = new GenericDialog("Indicate Range:");
			        	gd.addSlider("From: ", 1, numSlices, currentIdx, 1);
			        	gd.addSlider("To: ", 1, numSlices, currentIdx, 1);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						int beginIdx = (int)gd.getNextNumber();
						int endIdx = (int)gd.getNextNumber();
						if (endIdx<beginIdx) return;
						
						ImageStack predictedStack = predictedSample.getStack();
						ImageStack negativeSampleStack = negativeSample.getStack();
						
						for (int idx=beginIdx; idx<=endIdx; idx++) {
			 				negativeSampleStack.addSlice(predictedStack.getSliceLabel(idx), predictedStack.getProcessor(idx));
			 				negativeSample.setStack(null, negativeSampleStack);
			 				negativeSample.setSlice(negativeSample.getStackSize());
			 				negativeSample.changes = true;	
						}
						if (endIdx==numSlices && beginIdx==1) {
							predictedSample.changes=false;
							predictedSample.close();
							return;
						}	
						for (int idx=endIdx; idx>=beginIdx; idx--) {
							predictedStack.deleteSlice(idx);
							predictedLabelIndices.remove(idx-1);
							predictedLabelProbility.remove(idx-1);
						}
						predictedSample.setStack(null, predictedStack);
						//predictedSample.setSlice(idx);
						predictedSample.changes = true;
						//negativeWindow.updateImage(negativeSample);
		 				//negativeCanvas.setSize((int)newWidth, (int)newHeight);
		 				//negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
		 				//negativeWindow.pack();
			        }
			    }); 
			    
			    Button makePredictMontage = new Button("Make montage image"); 
			    makePredictMontage.addActionListener(new ActionListener() { 
			    	@Override 
				    public void actionPerformed(ActionEvent ae) {
				    	int column = 10;
				    	double scale = 5;
						GenericDialog gd = new GenericDialog("Make Montage Images");
						gd.addMessage("sample image number: " + String.valueOf(predictedSample.getStack().getSize()));
						gd.addNumericField("Column: ", column, 0);
						gd.addSlider("Zoom: ", 100, 1000, scale*100, 50);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						column = (int)gd.getNextNumber();
						if (column<1)	return;
						scale = gd.getNextNumber()/100;
				    	ImagePlus predictedMontage = makeMontage(predictedSample, column, scale);
				    	if (predictedMontage!=null)	predictedMontage.show();
				    } 
				}); 
			    
			    Button delete = new Button("delete (range)"); 
			    delete.addActionListener(new ActionListener() { 
			        @Override 
			        public void actionPerformed(ActionEvent ae) { 
			        	// use dialog for user input
			        	int currentIdx = predictedSample.getCurrentSlice();
			        	int numSlices = predictedSample.getNSlices();
			        	
			        	GenericDialog gd = new GenericDialog("Indicate Range:");
			        	gd.addSlider("From: ", 1, numSlices, currentIdx, 1);
			        	gd.addSlider("To: ", 1, numSlices, currentIdx, 1);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						int beginIdx = (int)gd.getNextNumber();
						int endIdx = (int)gd.getNextNumber();
						if (endIdx<beginIdx) return;
						
						ImageStack predictedStack = predictedSample.getStack();
						if (endIdx==numSlices && beginIdx==1) {
							predictedSample.changes=false;
							predictedSample.close();
							return;
						}	
						for (int idx=endIdx; idx>=beginIdx; idx--) {
							predictedStack.deleteSlice(idx);
							predictedLabelIndices.remove(idx-1);
							predictedLabelProbility.remove(idx-1);
						}
						predictedSample.setStack(null, predictedStack);
						//predictedSample.setSlice(idx);
						predictedSample.changes = true;
						//negativeWindow.updateImage(negativeSample);
		 				//negativeCanvas.setSize((int)newWidth, (int)newHeight);
		 				//negativeCanvas.setSourceRect(new Rectangle(0, 0, imgWidth, imgHeight));
		 				//negativeWindow.pack();
			        } 
			    });
			    predictWindow.add(sortByIndex); 
			    predictWindow.add(sortByProb);
			    predictWindow.add(makePredictMontage);
			    predictWindow.add(addToPositive);
			    predictWindow.add(addToNegative);
			    predictWindow.add(delete);
			    predictWindow.pack();
		    }
		});
		
		Button trainClassifier = new Button("train classifier");
		trainClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	
		    	ArrayList<Integer> positiveRoiIndices = getLabelIndicesFromStack(positiveSample);
		    	ArrayList<Integer> negativeRoiIndices = getLabelIndicesFromStack(negativeSample);
		    	
		    	if (positiveSample.getStack().getSize()==0 || positiveRoiIndices==null) {
		    		IJ.showMessage("sample error", "Label first a few positive samples to train a classifier!");
		    		return;
		    	}
		    	if (negativeSample.getStack().getSize()==0 || negativeRoiIndices==null) {
		    		IJ.showMessage("sample error", "Label first a few negative samples to train a classifier!");
		    		return;
		    	}
		    	
		    	if (imp==null) return;
		    	RoiManager rm = RoiManager.getInstance2();
		    	if (rm==null)	return;
		    	int nROI = rm.getCount();
		    	if (nROI==0)	return;
		    	Roi oriRoi = imp.getRoi();	imp.deleteRoi();
		    	ImagePlus impTrain = imp.duplicate();
		    	
		    	
		    	classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	classifierInfo.setText("Classifier training in progress...");
		    	classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	
		    	ArrayList<Attribute> atts = new ArrayList<Attribute>();
	
		    		//protected static String classLabel1 = null;
		    		//protected static String classLabel2 = null;
		    		
		    	for (int i=0; i<4; i++) {
		    		String featureName;
		    		if (channels[i]) {
		    			featureName = "C" + String.valueOf(i+1) + "_";
		    		} else {
		    			continue;
		    		}
		    		
	    			atts.add(new Attribute(featureName + "Mean"));
	    			atts.add(new Attribute(featureName + "StdDev"));
	    			if (feature==0) continue;
		    		
		    		atts.add(new Attribute(featureName + "CV"));
		    		if (feature==1)	continue;
		    		
	    			atts.add(new Attribute(featureName + "Skewness"));
	    			atts.add(new Attribute(featureName + "Kurtosis"));
	    			if (feature==2) continue;
		    	}
		    	
		    	ArrayList<String> attVals = new ArrayList<String>();
	    	    attVals.add(classLabel1);
	    	    attVals.add(classLabel2);
		    	atts.add(new Attribute("class", attVals));
		    	Instances trainingData = new Instances("ObjectSegmentation", atts, 0);

		    	//Collections.shuffle(noneMitosisId);
		    	//noneMitosisId.removeRange(nMitosis*1-negaMitosis, noneMitosisId.size());
		    	//noneMitosisId.addAll(negativeMitosisId);
		    	
		    	for (Integer p : positiveRoiIndices) {
		    		double[] vals = new double[trainingData.numAttributes()];
		    		int idx = 0;
		    		Roi roi = rm.getRoi(p);
		    		impTrain.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		    		impTrain.setRoi(roi, false);
		    		
		    		for (int i=0; i<4; i++) {
			    		if (channels[i]) {
			    			impTrain.setC(i+1);
			    		} else {
			    			continue;
			    		}
			    		vals[idx] = impTrain.getRawStatistics().mean; idx++;
			    		vals[idx] = impTrain.getRawStatistics().stdDev; idx++;
			    		if (feature==0) continue;
			    		if (feature>0)	{
			    			vals[idx] = (impTrain.getRawStatistics().mean==0)? 0 : (impTrain.getRawStatistics().stdDev / impTrain.getRawStatistics().mean); 
			    			idx++;
			    			continue;
			    		} 
			    		if (feature==2) {
			    			vals[idx] = impTrain.getAllStatistics().skewness; idx++;
			    			vals[idx] = impTrain.getAllStatistics().kurtosis; idx++;
			    			continue;
			    		}
			    	}
		    		vals[vals.length-1] = attVals.indexOf(classLabel1);
		    	    trainingData.add(new DenseInstance(1.0, vals));
		    	}
		    	for (Integer n : negativeRoiIndices) {
		    		double[] vals = new double[trainingData.numAttributes()];
		    		int idx = 0;
		    		Roi roi = rm.getRoi(n);
		    		impTrain.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		    		impTrain.setRoi(roi, false);
		    		
		    		for (int i=0; i<4; i++) {
			    		if (channels[i]) {
			    			impTrain.setC(i+1);
			    		} else {
			    			continue;
			    		}
			    		vals[idx] = impTrain.getRawStatistics().mean; idx++;
			    		vals[idx] = impTrain.getRawStatistics().stdDev; idx++;
			    		if (feature==0) continue;
			    		if (feature>0)	{
			    			vals[idx] = (impTrain.getRawStatistics().mean==0)? 0 : (impTrain.getRawStatistics().stdDev / impTrain.getRawStatistics().mean); 
			    			idx++;
			    			continue;
			    		} 
			    		if (feature==2) {
			    			vals[idx] = impTrain.getAllStatistics().skewness; idx++;
			    			vals[idx] = impTrain.getAllStatistics().kurtosis; idx++;
			    			continue;
			    		}
			    	}
		    		vals[vals.length-1] = attVals.indexOf(classLabel2);
		    	    trainingData.add(new DenseInstance(1.0, vals));
		    	}
		    	impTrain.close();
		    	//imp.setRoi(oriRoi);
		    	cls = new RandomForest();
		    	int classIdx=trainingData.numAttributes()-1;
		    	trainingData.setClassIndex(classIdx);
		    	try {
					cls.buildClassifier(trainingData);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		    	IJ.run("Collect Garbage", "");
		    	System.gc();
		    	classifierInfo.paintImmediately(0,0,classifierInfo.getWidth(), classifierInfo.getHeight());
		    	classifierInfo.setText("Classifier training complete.");
		    	imp.deleteRoi();
		    }
		});
		
		Button exportClassifier = new Button("export classifier");
		exportClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	if (cls==null) {
		    		classifierInfo.setText("No trained classifier found!\nTrain a classifier first, and then export.");
		    		return;
		    	}
		    	String savePath = null;
		    	JFileChooser fileChooser = new JFileChooser();
		    	fileChooser.setDialogTitle("Specify a file to save");
		    	int userSelection = fileChooser.showSaveDialog(f);
		    	if (userSelection == JFileChooser.APPROVE_OPTION) {
		    	    savePath = fileChooser.getSelectedFile().getAbsolutePath();
		    	}
		    	if (savePath==null)	return;
				try {
					SerializationHelper.write(savePath,cls);
					String firstLine = savePath; int sepIdx = 0; int loopCount=0;
					while(firstLine.length()>40) {
						loopCount++;
						if (loopCount>10)	break;
						sepIdx = firstLine.lastIndexOf(File.separator);
						firstLine = firstLine.substring(0, sepIdx);
					}
					String displaySavePath = firstLine + "\n" + savePath.substring(sepIdx, savePath.length());
					classifierInfo.setText("classifier saved as:\n" + displaySavePath);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					classifierInfo.setText("Failed to save classifier.");
					e.printStackTrace();
				}
		    } 
		});
		
		Button configureClassifier = new Button("configure classifier");
		configureClassifier.addActionListener(new ActionListener() { 
		    @Override 
		    public void actionPerformed(ActionEvent ae) {
				GenericDialog gd = new GenericDialog("Configure Classifier");
				int rows1=1, columns1=4;
				String[] headings1 = {"channels"};
				String[] labels1 = {"1","2","3","4",};
				boolean[] states1 = channels;
				gd.addCheckboxGroup(rows1, columns1, labels1, states1, headings1);
				String[] featureOptions = {"simple", "standard", "advanced"};
				gd.addChoice("object features", featureOptions, featureOptions[feature]);
				gd.addStringField("Class 1 label:", classLabel1);
				gd.addStringField("Class 2 label:", classLabel2);
				gd.addCheckbox("show result in ImageJ Log window", resultToLog);
				gd.showDialog();
				if (gd.wasCanceled()) return;
				channels[0] = gd.getNextBoolean();
				channels[1] = gd.getNextBoolean();
				channels[2] = gd.getNextBoolean();
				channels[3] = gd.getNextBoolean();
				feature = gd.getNextChoiceIndex();
				classLabel1 = gd.getNextString();
				classLabel2 = gd.getNextString();
				resultToLog = gd.getNextBoolean();
			}
		});
		
		classifierPanel.add(classifierInfo);
		classifierPanel.add(checkClassifier);
		classifierPanel.add(loadClassifier);
		classifierPanel.add(applyClassifier);
		classifierPanel.add(trainClassifier);
		classifierPanel.add(exportClassifier);
		classifierPanel.add(configureClassifier);
		
		classifierPanel.setMinimumSize(new Dimension(100, 300));
		classifierPanel.setMaximumSize(new Dimension(300, 500));
		//classifierPanel.setAlignmentX(Component.LEFT_ALIGNMENT );
		
		f.add(classifierPanel);
	}
	
	public static void createFrontalPanel(
			//ImagePlus imp
			) throws Exception {
		
		/* create a frontal panel with 3 major component: sample image and ROIs; labels, classifier
		 * 
		 * 
		 * 
		 * 
		 * 
		 * 
		 */
		
		PlugInFrame pf = new PlugInFrame("classifier");
		pf.setLayout(new BoxLayout(pf, BoxLayout.Y_AXIS));
		
		
		
		// configure and create the "Labels" panel
		Label labelTitle = new Label("Labels:");
		labelTitle.setFont(new Font("Helvetica", Font.BOLD, 12));
		labelTitle.setMaximumSize(new Dimension(500, 10));
		
		JPanel labelPanel = new JPanel();
		labelPanel.setLayout(new BoxLayout (labelPanel, BoxLayout.Y_AXIS));
		labelPanel.setBorder(new EmptyBorder(new Insets(10, 15, 10, 10)));
		labelPanel.setBackground(pf.getBackground());
		labelPanel.setMaximumSize(new Dimension(500, 200));
		//labelPanel.setAlignmentX(Component.LEFT_ALIGNMENT );
		
		int positiveSampleSize = positiveSample==null?0:positiveSample.getStack().size();
		int negativeSampleSize = negativeSample==null?0:negativeSample.getStack().size();
		
		String posLabelText = "positive samples: " + String.valueOf(positiveSampleSize);
		JTextArea positiveLabel = new JTextArea(posLabelText); 
		positiveLabel.setEditable(false);
		String negLabelText = "negative samples: " + String.valueOf(negativeSampleSize);
		JTextArea negativeLabel = new JTextArea(negLabelText);
		negativeLabel.setEditable(false);
		
		String labelBalanceText = "Training sample balanced.";
		labelBalanceText = (negativeSampleSize<positiveSampleSize)?"Unbalanced training samples!\nNot enough negative samples.":labelBalanceText;
		labelBalanceText = (positiveSampleSize<negativeSampleSize/10)?"Unbalanced training samples!\nNot enough positive samples.":labelBalanceText;
		
		JTextArea labelBalanceLabel = new JTextArea(labelBalanceText);
		labelBalanceLabel.setFont(new Font("Helvetica", Font.BOLD, 12));
		labelBalanceLabel.setEditable(false);
		labelPanel.add(positiveLabel);
		labelPanel.add(negativeLabel);
		labelPanel.add(labelBalanceLabel);
		
		// configure and create the "Classification" panel
		
		/*
		JPanel classifierPanel = new JPanel();
		
		classifierPanel.setLayout(new BoxLayout (classifierPanel, BoxLayout.Y_AXIS));
		classifierPanel.setBorder(new EmptyBorder(new Insets(10, 15, 10, 100)));
		classifierPanel.setBackground(pf.getBackground());
		
		JPanel classifierButtonPanel = new JPanel();
		classifierButtonPanel.setLayout(new GridLayout(5, 1, 3, 3));
		//classifierButtonPanel.setMaximumSize(new Dimension(120, 100));
		classifierButtonPanel.setBorder(new EmptyBorder(new Insets(10, 15, 10, 100)));
		classifierButtonPanel.setBackground(pf.getBackground());
		
		Button loadClassifier = new Button("load classifier");
		Button applyClassifier = new Button("apply classifier");
		Button trainClassifier = new Button("train classifier");
		Button exportClassifier = new Button("export classifier");
		Button configureClassifier = new Button("configure classifier");
		classifierButtonPanel.add(loadClassifier);
		classifierButtonPanel.add(applyClassifier);
		classifierButtonPanel.add(trainClassifier);
		classifierButtonPanel.add(exportClassifier);
		classifierButtonPanel.add(configureClassifier);
		
		//classifierPanel.add(classifierButtonPanel);
		//pf.add(window);pf.add(canvas);pf.add(window2);

		Button refresh = new Button("refresh");
		refresh.addActionListener(new ActionListener() {
			@Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	posLabelText = "positive samples: " + String.valueOf(sampleImage.getStack().size());
		    	positiveLabel.setText(posLabelText);
		    	negLabelText = "negative samples: " + String.valueOf(sampleImage2.getStack().size());
		    	negativeLabel.setText(negLabelText);
		    }
		});
		 */
		
		
		
		
		
		//pf.add(sampleTitle);
		//pf.add(samplePanel);
		/*
		Label sampleTitle = new Label("Image and ROI:");
		sampleTitle.setFont(new Font("Helvetica", Font.BOLD, 12));
		sampleTitle.setMaximumSize(new Dimension(500, 10));
		
		JPanel samplePanel = new JPanel();
		//samplePanel.setLayout(new BoxLayout (labelPanel, BoxLayout.Y_AXIS));
		
		
		samplePanel.setLayout(new GridLayout(2, 1));
		//classifierButtonPanel.setMaximumSize(new Dimension(120, 100));
		samplePanel.setBorder(new EmptyBorder(new Insets(10, 15, 10, 10)));
		samplePanel.setBackground(pf.getBackground());
		samplePanel.setMaximumSize(new Dimension(500, 200));
		
		JTextArea imgInfo = new JTextArea(); 
		positiveLabel.setEditable(false);
		String imgInfoText = "current image:\n   " + imp.getTitle();
		imgInfo.setText(imgInfoText);
		
		
		Button refresh = new Button("refresh source");
		refresh.addActionListener(new ActionListener() {
			@Override 
		    public void actionPerformed(ActionEvent ae) { 
		    	imp = WindowManager.getCurrentImage();
		    	imgInfo.setText("current image:\n   " + imp.getTitle());
			}
		});
		samplePanel.add(imgInfo);
		samplePanel.add(refresh);
		
		
		
		
		pf.add(sampleTitle);
		pf.add(samplePanel);
		*/
		
		addSamplePanel(pf);
		
		pf.add(labelTitle);
		pf.add(labelPanel);
		//pf.add(classifierTitle);
		addClassificationPanel(pf);
		//pf.add(classifierButtonPanel);

		
		
		
		WindowManager.addWindow(pf);
		
		
		pf.pack();
		pf.setSize(positiveSample.getWindow().getWidth(), (int) (positiveSample.getWindow().getHeight()*1.3));
		pf.setVisible(true);
		pf.setLocationRelativeTo(null);
		GUI.center(pf);
		
		//pf.setBounds(window2.getX()+window2.getWidth()-10, window2.getY(), window.getWidth(), window.getHeight());
		
		//pf.setBounds(window2.getX()+window2.getWidth()-10, window2.getY(), window.getWidth(), window.getHeight());
		pf.setResizable(false);	//fix the plugin frame size
		
		Timer timer = new Timer(550, new ActionListener() {
	        public void actionPerformed(ActionEvent evt) {
	        	
	        	
	        	
	        	// handle updated status of the label panel:
	        	int positiveSampleSize = positiveSample.getStack().size();
	        	int negativeSampleSize = negativeSample.getStack().size();
	        	
		    	positiveLabel.setText("positive samples: " + String.valueOf(positiveSampleSize));
		    	negativeLabel.setText("negative samples: " + String.valueOf(negativeSampleSize));
		    	
		    	String labelBalanceText = "Training sample balanced.";
				labelBalanceText = (negativeSampleSize<positiveSampleSize)?"Unbalanced training samples!\nNot enough negative sample labels.":labelBalanceText;
				labelBalanceText = (positiveSampleSize<negativeSampleSize/10)?"Unbalanced training samples!\nNot enough positive sample labels.":labelBalanceText;
				labelBalanceLabel.setFont(new Font("Helvetica", Font.BOLD, 12));
				labelBalanceLabel.setText(labelBalanceText);
		    	
				labelPanel.repaint();
				
				
	        }
	    });
		timer.start();
		
		positiveSample.getWindow().setLocation((int)(pf.getBounds().getX()-pf.getBounds().getWidth()), (int)(pf.getBounds().getY()+positiveSample.getWindow().getHeight()*0.3));
		negativeSample.getWindow().setLocation((int)(pf.getBounds().getX()+pf.getBounds().getWidth()), (int)(pf.getBounds().getY()+negativeSample.getWindow().getHeight()*0.3));
		
	}
	
	@Override
	public void run(String arg) {
		
		/*
		OpenDialog od = new OpenDialog("Load classifier");
		String modelPath = od.getPath();
		if (modelPath==null)	return;
		
		Classifier cls = null;
		try {
			cls = (Classifier) SerializationHelper.read(modelPath);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ArrayList<String> featureAttributes = new ArrayList<String>();
		try {
			featureAttributes = WekaUtility.getClassAttributeFromModel(cls);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		for (String feature : featureAttributes) {
			IJ.log(feature);
		}
		*/

		//ImagePlus impOld = IJ.getImage();
		ImagePlus impOri = WindowManager.getCurrentImage();
		if (impOri==null) {
			IJ.error("No image open!");
			return;
		}
		imp = impOri;
		
		
		RoiManager rm = RoiManager.getInstance2();
		if (rm==null) {
			new MitosisDetector().run(null);
			rm = RoiManager.getInstance2();
			if (rm==null) return;
			if (rm.getCount()<1)	return;
		}
		
		//ImagePlus imp = impOld.duplicate();
		/*
		Roi oriRoi = impOri.getRoi();
		impOri.deleteRoi();
		imp = impOri.duplicate();
		impOri.setRoi(oriRoi);
		*/
		/*
		imp = new ImagePlus();
		if (impOld.isComposite()) {
			if (((CompositeImage)impOld).getActiveChannels()[imp.getNChannels()-1]) {	// check if the brightfield channel is acitve
				imp = new Duplicator().run(impOld, 1, impOld.getNChannels(), 1, impOld.getNSlices(), 1, impOld.getNFrames());
			} else {
				imp = new Duplicator().run(impOld, 1, impOld.getNChannels()-1, 1, impOld.getNSlices(), 1, impOld.getNFrames());
			}
		} else {
			IJ.showMessage("input image error", "Active image is not valid for training multi-channel fluorescent classifier!");
			return;
		}
		*/
		//imp.setActiveChannels(channels);
		boolean[] active = ((CompositeImage)imp).getActiveChannels();
		for (int c=0; c<3; c++) {
			//imp.setC(c+1);	impOld.setC(c+1);
			//imp.setDisplayRange(impOld.getDisplayRangeMin(), impOld.getDisplayRangeMax());
			if(!active[c]) {
				activeChannels = activeChannels.substring(0, c) + "0" + activeChannels.substring(c+1);
			} 
			/*
			else {
				imp.setC(c+1);
				IJ.run(imp, "Enhance Contrast", "saturated=0.35");
			}
			*/
		}


		
		
		//modifySamples(imp);
		modifySamples();
		System.gc();
		try {
			createFrontalPanel();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.gc();
		//imp.close();
		//imp.flush();
		//System.gc();
		// end = System.currentTimeMillis();
		//duration = (end-start)/1000;
		//IJ.log("duration: " + String.valueOf(duration) + " seconds");
		
		
	}
	
public static void main(String[] args) {
		
		String [] ij_args = 
			{ "-Dplugins.dir=C:/Fiji.app/plugins",
			"-Dmacros.dir=C:/Fiji.app/macros" };

		ij.ImageJ.main(ij_args);
		
		MitosisTrainableClassifier mc = new MitosisTrainableClassifier();
		mc.run(null);
		
	
	}
	
	
	
}
