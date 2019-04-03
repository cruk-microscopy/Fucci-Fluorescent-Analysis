package uk.ac.cam.cruk.fglab;

import java.text.DecimalFormat;

import ij.IJ;
import ij.ImagePlus;

public class GetImageInfo {

	public static void getInfo (
			ImagePlus imp
			) {
		if (imp == null) return;
		String title = imp.getTitle();
		String MBSize = new DecimalFormat(".#").format(imp.getSizeInBytes()/1048576);
		int[] dim = imp.getDimensions();
		
		String unit = imp.getCalibration().getUnit();
		String tUnit = imp.getCalibration().getTimeUnit();
		String xPxSize = new DecimalFormat(".##").format(imp.getCalibration().pixelWidth);
		String yPxSize = new DecimalFormat(".##").format(imp.getCalibration().pixelHeight);
		String zPxSize = new DecimalFormat(".##").format(imp.getCalibration().pixelDepth);
		String tPxSize = new DecimalFormat(".##").format(imp.getCalibration().frameInterval);

		IJ.log("      Image: " + title + " (" + MBSize + "MB)");
		IJ.log("      Dimension: X:" + dim[0] + ", Y:" + dim[1] + ", Z:" + dim[3]
				 + "  with Voxel Size: " + xPxSize + " * " + yPxSize + " * " + zPxSize + " " + unit);
		IJ.log("      Frame Number (T): " + dim[4] + " with Frame Interval: " + tPxSize + " " + tUnit);
		IJ.log("      Channel Number: " + dim[2]);
	}
}
