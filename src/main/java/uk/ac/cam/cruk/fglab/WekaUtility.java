package uk.ac.cam.cruk.fglab;

import java.awt.Rectangle;
import java.util.ArrayList;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.ShapeRoi;


import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.converters.ArffLoader;
import weka.core.converters.ConverterUtils.DataSink;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.SerializationHelper;
import weka.classifiers.Evaluation;
import weka.classifiers.trees.RandomForest;
import weka.classifiers.Classifier;


public class WekaUtility {

	public static boolean getPredictionResult(
			String modelPath,
			Instances data
			) {
		
		Classifier rf;
		try {
			rf = (Classifier) SerializationHelper.read(modelPath);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		
		int classIdx=data.numAttributes()-1;
		data.setClassIndex(classIdx);
		double label = 0;
		try {
			label = rf.classifyInstance(data.instance(0));
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
		data.instance(0).setClassValue(label);
		//IJ.log("debug: WekaUtility, data class value: " + data.instance(0).toString(classIdx));
		return (data.instance(0).toString(classIdx).equals("yes"));

	}
	
	public static boolean saveTrainingDataAndModel(
			
			) {
		
		return true;
	}
	
	public static Instances createTrainingData (
			ImagePlus imp,
			Roi roi
			) {
		
	    ArrayList<Attribute>	atts;
	    ArrayList<Attribute>	attsRel;
	    ArrayList<String>		attVals;
	    ArrayList<String>		attValsRel;
	    Instances			data;
	    Instances			dataRel;
	    double[]			vals;
	    double[]			valsRel;
	    int				i;

		// parse input image
		int n = imp.getCurrentSlice();
		int numC = imp.getNChannels();
		//imp.hide();
		
		imp.getCalibration().pixelWidth = 1.0; imp.getCalibration().pixelHeight = 1.0;
		imp.getCalibration().setUnit("pixel");

	    // 1. set up attributes
	    atts = new ArrayList<Attribute>();
	    // - numeric
	    for (int c=0; c<numC; c++) {
	    	String featureName = "Original_C" + String.valueOf(c+1) + "_";
		    atts.add(new Attribute(featureName + "Mean"));
		    atts.add(new Attribute(featureName + "Variance"));
		    atts.add(new Attribute(featureName + "StdDev"));
		    atts.add(new Attribute(featureName + "AStdDev"));
		    atts.add(new Attribute(featureName + "L2norm"));
		    atts.add(new Attribute(featureName + "Skewness"));
		    atts.add(new Attribute(featureName + "Kurtosis"));
	    }
		// add feature images at different sigma
		double[] sigma = {0.3, 0.7, 1.0, 1.6, 3.5, 5.0};
		String[] features = {"Gaussian", "Laplacian", "Hessian", "Structure", "Edge"};
		for (int s=0; s<sigma.length; s++) {
			for (int f=0; f<features.length; f++) {
				for (int c=0; c<numC; c++) {
					String featureName = features[f] + "_" + String.valueOf(sigma[s]) + "_C" + String.valueOf(c+1) + "_";
					atts.add(new Attribute(featureName+"Mean"));
					atts.add(new Attribute(featureName+"Variance"));
					atts.add(new Attribute(featureName+"StdDev"));
					atts.add(new Attribute(featureName+"AStdDev"));
					atts.add(new Attribute(featureName+"L2norm"));
					atts.add(new Attribute(featureName+"Skewness"));
					atts.add(new Attribute(featureName+"Kurtosis"));
				}
			}
		}
		
		//String labeledFile = "I:/core/light_microscopy/data/group_folders/Ziqiang/ZH_Sarah/WTS_test/WTS/mtTraining/trainingData/trainingSet5.arff";
		//Attribute class = getClassAttrFromLabeledFile(labeledFile);
		attVals = new ArrayList<String>();
		attVals.add("no");
		attVals.add("yes");
		atts.add(new Attribute("class", attVals));
	    //atts.add(getClassAttrFromLabeledFile(labeledFile));
		
	    // 2. create Instances object
	    data = new Instances("ObjectSegmentation", atts, 0);

		//roi = rm.getRoi(r).clone();
		//imp.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
		//imp.setRoi(roi, false);
		//impCrop = new Duplicator().run(imp, 1, imp.nChannels, 1, imp.nSlices, imp.getT(), imp.getT() );
		//imp.deleteRoi();
		roi = cropRoi(imp, roi);
		//roi.setLocation(0,0);
		
		ImagePlus[] featureImages = ImageFeatureUtility.createFeatureImages(imp);
		
		vals = new double[data.numAttributes()];
		
		int idx = 0;
		
		double[] valsOri  = new double[7]; // 7 statistical features for each channel in original image
		
		for (int c=0; c<numC; c++) {
			imp.setC(c+1);
			valsOri = ImageFeatureUtility.getAdvancedImageStatistics(imp.getChannelProcessor(), roi);
			for (int v=0; v<valsOri.length; v++) {
				vals[idx] = valsOri[v];
				++idx;
			}// vals + 7 attributes for current channel
		}// 7 measure,4 channel = 28 attributes
		//IJ.log("debug: WekaUtility, createTrainingData 148: ori_mean: " + String.valueOf(vals[0]));
		double[] valsFeature = new double[7];
		
	    for (int f=0; f<featureImages.length; f++) {
	    	//impF = featureImages[f];
			for (int c=0; c<numC; c++) {
	    		featureImages[f].setC(c+1);
	    		valsFeature = ImageFeatureUtility.getAdvancedImageStatistics(featureImages[f].getChannelProcessor(), roi);
	    		for (int v=0; v<valsFeature.length; v++) {
					vals[idx] = valsFeature[v];
					++idx;
				}// vals + 7 attributes for current channel
			}// vals + 28 attributes for current feature
			//impF.close();
			featureImages[f].close();
	    }// vals + 840 attributes for all features
	    
	    vals[vals.length-1] = Utils.missingValue();

		data.add(new DenseInstance(1.0, vals));
		
		return data;
	}
	
	
	public static Roi cropRoi(
			ImagePlus imp, 
			Roi roi
			) {
		
		if (roi==null)
			return null;
		if (imp==null)
			return roi;
		Rectangle b = roi.getBounds();
		int w = imp.getWidth();
		int h = imp.getHeight();
		if (b.x<0 || b.y<0 || b.x+b.width>w || b.y+b.height>h) {
			ShapeRoi shape1 = new ShapeRoi(roi);
			ShapeRoi shape2 = new ShapeRoi(new Roi(0, 0, w, h));
			roi = shape2.and(shape1);
		}
		if (roi.getBounds().width==0 || roi.getBounds().height==0)
			throw new IllegalArgumentException("Selection is outside the image");
		return roi;
	}		
}
