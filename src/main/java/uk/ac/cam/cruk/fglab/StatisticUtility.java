package uk.ac.cam.cruk.fglab;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import ij.gui.Roi;
import ij.plugin.filter.GaussianBlur;

import imagescience.image.Image;
import imagescience.feature.Laplacian;
import imagescience.feature.Hessian;
import imagescience.feature.Edges;
import imagescience.feature.Structure;


public interface StatisticUtility {

	
	// function to get min and max in one go
	public static double[] getMinMax (double[] data) {
		if (data==null) return null;
		double N = (double) data.length;
		if (N==0) return null;
		if (N==1) return new double[] {data[0], data[0]};
		
		double min = Double.MAX_VALUE; double max = Double.MIN_VALUE;
		for (double value : data) {
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		return new double[] {min, max};
	}
	
	public static double normalizeDataByRange (double value, double min, double max) {
		if (max==min) return Double.NaN;
		return ( (value - min) / (max - min) );
	}
	
	// get area, location (x, y, t), stats (mean and stdDev of all channels), shape?
	public static double[] getMeasurements (ImagePlus imp, int index, HashMap<String, double[]> map) {
		if (imp==null || index<0 || map==null) return null;
		ArrayList<Double> measurements = new ArrayList<Double>();
		//double weight1 = 1; double weight2 = 1; double weight3 = 10;
		measurements.add(map.get("area")[index]);
		//measurements.add(map.get("X")[index]);
		//measurements.add(map.get("Y")[index]);
		//measurements.add(map.get("T")[index]);
		measurements.add(map.get("major")[index]);
		measurements.add(map.get("minor")[index]);
		measurements.add(map.get("C1mean")[index]);
		measurements.add(map.get("C1stdDev")[index]);
		measurements.add(map.get("C2mean")[index]);
		measurements.add(map.get("C2stdDev")[index]);
		measurements.add(map.get("C3mean")[index]);
		measurements.add(map.get("C3stdDev")[index]);
		double[] result = new double[measurements.size()];
		for (int i=0; i<measurements.size(); i++) { result[i] = measurements.get(i); }
		return result;
	}
	
	
	// get Jensen-Shannon divergence between two 1d vector
	public static double getJSD (double[] p, double[] q) {
		if (p==null || q==null || p.length!=q.length) return Double.NaN;
		return ( (double) 2 / ( getKLD(p, q) + getKLD(q, p) ) );
		//return ( ( getKLD(p, q) + getKLD(q, p) ) / (double) 2 );
	}
	// get Kullback–Leibler divergence between two 1d vector
 	public static double getKLD (double[] p, double[] q) { // normalize or not?
 		if (p==null || q==null || p.length!=q.length) return Double.NaN;
 		int size = p.length;
 		double SUM = 0;
 		for (int i=0; i<size; i++) {
 			if (p[i]==0 || q[i]==0) continue;
 			SUM += p[i] * ( Math.log( p[i]/q[i] ) / Math.log(2) );
 		}
 		return SUM;
 	}
 	
	public static double[] getAdvancedImageStatistics(	// return dMean, dVariance, dSDeviation, dADeviation, dL2norm, dSkewness, dKurtosis
			ImageProcessor ip,
			Roi r
			) {
			
			if (r == null) return null;
			Point[] p = r.getContainedPoints();

			// Compute statistics:
			double dElements=0.0;
			double dSum1=0.0; 
			double dSum2=0.0;
			double dSum3=0.0;
			double dSum4=0.0;
			double dASum=0.0;
			double dMinimum = Double.MAX_VALUE; 
			double dMaximum = -Double.MAX_VALUE;
			
			for (int i=0; i<p.length; ++i) {
				//double val1 = ip.getf(p[i].x,p[i].y);
				double val1 = ip.getPixelValue((int)p[i].x,(int)p[i].y);
				//println(val1);
				final double val2 = val1*val1;
				dSum1 += val1;
				dSum2 += val2;
				dSum3 += val1*val2;
				dSum4 += val2*val2;
				dASum += Math.abs(val1);
				if (val1 < dMinimum) dMinimum = val1;
				if (val1 > dMaximum) dMaximum = val1;
				++dElements;
			}
			
			double dMass = dSum1;
			double dMean = dSum1/dElements;
			
			final double dMean2 = dMean*dMean;
			double dVariance = (dSum2/dElements) - dMean2;
			double dSDeviation = Math.sqrt(dVariance);
			double dL1norm = dASum;
			double dL2norm = Math.sqrt(dSum2);
			double dSkewness = ((dSum3 - 3.0*dMean*dSum2)/dElements + 2.0*dMean*dMean2)/(dVariance*dSDeviation);
			double dKurtosis = (((dSum4 - 4.0*dMean*dSum3 + 6.0*dMean2*dSum2)/dElements - 3.0*dMean2*dMean2)/(dVariance*dVariance)-3.0);
			
			final int BINS = 100000;
			double dADevSum=0.0;
			final double dRange = dMaximum - dMinimum;
			final double dScale = (dRange == 0.0) ? 1 : dRange;
			final int[] hist = new int[BINS+1];
			final double dNBins = BINS;
			
			for (int j=0; j<p.length; ++j) {
				++hist[(int)(((ip.getf((int)p[j].x,(int)p[j].y) - dMinimum)/dScale)*dNBins)];
				dADevSum += Math.abs(ip.getf((int)p[j].x,(int)p[j].y) - dMean);
			}
			
			double dADeviation = dADevSum/dElements;
			double dMode = 0;
			double dMedian = 0;
			hist[BINS-1] += hist[BINS];
			int iMax = 0, iCum = 0;
			final int iElements = (int)dElements;
			final int iHalfway = (iElements%2 == 0) ? (iElements/2) : (iElements/2 + 1);
			for (int k=0; k<BINS; ++k) {
				final int iVal = hist[k];
				if (iVal >= iMax) { iMax = iVal; dMode = k; }
				if (iCum < iHalfway) { dMedian = k; iCum += iVal; }
			}
			dMode = dMinimum + ((dMode + 0.5)/dNBins)*dRange;
			dMedian = dMinimum + ((dMedian + 0.5)/dNBins)*dRange;
			
			double[] result = {dMean, dVariance, dSDeviation, dADeviation, dL2norm, dSkewness, dKurtosis};
			return result;
		}	
	
	public static ImagePlus[] createFeatureImages(ImagePlus imp) { // return 30 feature images
		
		double[] sigma = {0.3, 0.7, 1.0, 1.6, 3.5, 5.0};	// 6 sigma level taken from iLastik
		// features: [original, Gaussian blur, Laplacian of Gaussian, Hessian, Structure tensor, Edge]
		int numC = imp.getNChannels();
		// create 5 feature images at each sigma
		ImagePlus[] featureImg = new ImagePlus[30];
		Image fjImg = Image.wrap(imp);
		
		for (int i=0; i<sigma.length; i++) {
			featureImg[i*5] = imp.duplicate();
			for (int c=0; c<numC; c++) {
				featureImg[i*5].setC(c+1);
				new GaussianBlur().blurGaussian(featureImg[i*5].getProcessor(), sigma[i]);
			}
			//new GaussianBlur().blurGaussian(featureImg[i*5].getProcessor(), sigma[i]);
			featureImg[i*5 + 1] = new Laplacian().run(fjImg, sigma[i]).imageplus();
			featureImg[i*5 + 2] = new Hessian().run(fjImg, sigma[i], false).get(0).imageplus();
			featureImg[i*5 + 3] = new Structure().run(fjImg, 1.0, sigma[i]).get(0).imageplus();
			featureImg[i*5 + 4] = new Edges().run(fjImg, sigma[i], false).imageplus();
		}
		return featureImg;
	}
	
	
	
}
