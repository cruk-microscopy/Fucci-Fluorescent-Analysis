package uk.ac.cam.cruk.fglab;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.gui.Plot;
import ij.process.ImageStatistics;
import ij.gui.PlotWindow;
import ij.gui.Roi;

import java.awt.Font;
import java.util.ArrayList;
import java.awt.Color;


public class GeneratePlot implements PlugIn {

	
	

public static void plotWithImgAndRoiManager(
		ImagePlus imp
		) {
	//imp = IJ.getImage();
	//impOri.deleteRoi();
	//ImagePlus imp = impOri.duplicate();
	RoiManager rm = RoiManager.getInstance();
	if (rm == null) return;
	Roi[] roiArray = RoiManagerUtility.managerToRoiArray();
	
	int numSpot = rm.getCount();

	
	double[] t = new double[numSpot];
	double[] c1 = new double[numSpot];
	double[] c2 = new double[numSpot];
	double[] c3 = new double[numSpot];
	ArrayList<Double> mitosisIdx = new ArrayList<Double>();
	double maxValue = 0;
	
	for (int i=0; i<roiArray.length; i++) {
		imp.setPositionWithoutUpdate(
				roiArray[i].getCPosition(), 
				roiArray[i].getZPosition(), 
				roiArray[i].getTPosition());
		imp.setRoi(roiArray[i], false);
		t[i] = (double)imp.getFrame();

		imp.setPositionWithoutUpdate( 1, 
				roiArray[i].getZPosition(), 
				roiArray[i].getTPosition());
		ImageStatistics stats = imp.getStatistics();
		c1[i] = stats.mean;
		imp.setPositionWithoutUpdate( 2, 
				roiArray[i].getZPosition(), 
				roiArray[i].getTPosition());
		stats = imp.getStatistics();
		c2[i] = stats.mean;
		imp.setPositionWithoutUpdate( 3, 
				roiArray[i].getZPosition(), 
				roiArray[i].getTPosition());
		stats = imp.getStatistics();
		c3[i] = stats.mean;
		
		if (roiArray[i].getName().toLowerCase().contains("m")) {
			mitosisIdx.add(t[i]);
		}
		maxValue = Math.max(c1[i], maxValue);
		maxValue = Math.max(c2[i], maxValue);
		maxValue = Math.max(c3[i], maxValue);
	}
	
	//imp.close();
	System.gc();
	//float[] x = {0.1f, 0.25f, 0.35f, 0.5f, 0.61f,0.7f,0.85f,0.89f,0.95f}; // x-coordinates
    //float[] y = {2f,5.6f,7.4f,9f,9.4f,8.7f,6.3f,4.5f,1f}; // x-coordinates

    PlotWindow.noGridLines = false; // draw grid lines
    
    Plot plot = new Plot("Fucci Fluorescent Plot","Time","Fluorescent intensity");

    plot.setColor(Color.red);
    //plot.addPoints(t,c3,PlotWindow.X);
    plot.addPoints(t,c1,Plot.LINE);
    
    plot.setColor(Color.green);
    //plot.addPoints(t,c3,PlotWindow.X);
    plot.addPoints(t,c2,Plot.LINE);
    // add label
    plot.setColor(Color.blue);
    plot.addPoints(t,c3,Plot.LINE);
    //plot.setColor(Color.red);
	//plot.addLegend("red\t1st Channel");
	//plot.setColor(Color.green);
    plot.setColor(new Color(185, 13, 179, 255));
    maxValue = 1000*Math.ceil(maxValue/1000);
	for (Double m : mitosisIdx) {
		int size = (int)maxValue;
		double[] xvalues = new double[size];
		double[] yvalues = new double[size];
		for (int i=0; i<size; i+=100) {
			xvalues[i] = m;
			yvalues[i] = i;
		}
		plot.add("cross", xvalues,yvalues);
	}
	plot.setColor(Color.black);
	plot.addLegend("1st Channel\t2nd Channel\t3rd Channel\tmitosis");
    plot.show();
}
	
	@Override
	public void run(String arg) {
		plotWithImgAndRoiManager(IJ.getImage());
	}
	public static void main(String[] args) {

		
		
		
		
		GeneratePlot gp = new GeneratePlot();
		gp.run(null);
		
		
	}
}
