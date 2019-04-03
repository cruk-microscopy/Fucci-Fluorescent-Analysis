package uk.ac.cam.cruk.fglab;

import ij.IJ;
import ij.ImagePlus;
//import ij.plugin.filter.BackgroundSubtracter;

public class BackgroundSubstraction {

	//protected static ImagePlus impSubtracted;
	//protected static double radius = 10;
	//protected static boolean useParaboloid = false;
	//protected static boolean doPresmooth = false;
	protected static String param = "rolling=10 stack";
	
	public ImagePlus subtractBackground() {
		ImagePlus imp = IJ.getImage();
		IJ.run(imp, "Subtract Background...", param);
		return imp;
	}
	
	public ImagePlus subtractBackground(
			ImagePlus imp
			) {
		IJ.run(imp, "Subtract Background...", param);
		return imp;
	}
	
	public ImagePlus subtractBackground(
			ImagePlus imp, 
			double radius
			) {
		param = "rolling=" + String.valueOf(radius) +" stack";
		IJ.run(imp, "Subtract Background...", param);
		return imp;
	}

	public ImagePlus subtractBackground(
			ImagePlus imp, 
			double radius, 
			boolean useParaboloid, 
			boolean doPresmooth
			) {
	
		String param = "rolling=" + String.valueOf(radius);
		if (useParaboloid) {
			param += " sliding" ;
		}
		if (!doPresmooth) {
			param += " disable" ;
		}
		param += " stack";

		IJ.run(imp, "Subtract Background...", param);
		
		return imp;
	}
}