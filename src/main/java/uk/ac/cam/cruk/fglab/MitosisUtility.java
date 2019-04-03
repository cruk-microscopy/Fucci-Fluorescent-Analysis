package uk.ac.cam.cruk.fglab;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;
import ij.process.StackStatistics;
import weka.core.Instances;

import org.jgrapht.graph.DefaultWeightedEdge;

public class MitosisUtility {

	public static String modelDir = IJ.getDirectory("imagej") + File.separator + "FucciToolbox";
	
	
	public static boolean makeModelDir () {
		File model = new File(modelDir);
		if (!model.exists()) {
			return model.mkdir();
		}
		return true;
	}
	
	
	public static ImagePlus saveTrackStack(
			ImagePlus imp,
			TrackModel trackmodel,
			Spot start,
			Spot end,
			double maxDiameter
			) {

			double pixelSize = imp.getCalibration().pixelWidth;
			maxDiameter *= 3;
			int dMax = (int)(maxDiameter);
			
			ArrayList<ImagePlus> imps = new ArrayList<ImagePlus>();
			ImagePlus imp2 = null;
			//ArrayList<ImagePlus> imps = new ArrayList<ImagePlus>();
			
			List<DefaultWeightedEdge> daughterTrackEdges = 
					trackmodel.dijkstraShortestPath(start, end);	

			int t_current = 0;
			
			for (DefaultWeightedEdge edge : daughterTrackEdges) {
				Spot source = trackmodel.getEdgeSource(edge);
				t_current = (int) Math.round(source.getFeature("FRAME"));
				
				double x = source.getFeature("POSITION_X");
				double y = source.getFeature("POSITION_Y");
				
				imp.setT(t_current);
				int xBounds = (int)((x-maxDiameter/2)/pixelSize);
				int yBounds = (int)((y-maxDiameter/2)/pixelSize);
				imp.setRoi(xBounds, yBounds, dMax, dMax);

				imp2 = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), t_current, t_current);
				
				IJ.run(imp2, "Canvas Size...", "width="+String.valueOf(dMax)+" height="+String.valueOf(dMax)+" position=Center zero");
				if (imp2 != null)
					imps.add(imp2); 
			}
			imp2.close();
			ImagePlus[] impsA = imps.toArray(new ImagePlus[0]);
			ImagePlus impFinal = Concatenator.run(impsA);
			return impFinal;
		}
	
	public static double[] branchLength(
			TrackModel trackmodel,
			Spot spotMitosis
			) {

				double t_mitosis = spotMitosis.getFeature("FRAME");
				
				TimeDirectedNeighborIndex neighborIndex  = trackmodel.getDirectedNeighborIndex();
				Set <Spot> predecessors = neighborIndex.predecessorsOf(spotMitosis);
				Set <Spot> successors = neighborIndex.successorsOf(spotMitosis);

				Spot mother = null;
				for (Spot spot : predecessors) {
					mother = spot;
				}
				Spot daughter = null;	Spot son = null;
				int i = 0;
				for (Spot spot : successors) {
					if (i == 0) {
						daughter = spot;
						i++;
					} else {
						son = spot;
					}
				}

				int nSource = 1;
				int nTarget = 1;

				Spot sourceSpot = mother;
				while (nSource == 1) {
					Set <Spot> newSourceSpot = neighborIndex.predecessorsOf(sourceSpot);
					nSource = newSourceSpot.size();
					for(Spot spot : newSourceSpot) {
					    sourceSpot = spot;
					    break;
					}
				}
				double t_mother = sourceSpot.getFeature("FRAME");

				Spot targetSpot = daughter;
				while (nTarget == 1) {
					Set <Spot> newTargetSpot = neighborIndex.successorsOf(targetSpot);
					nTarget = newTargetSpot.size();
					for(Spot spot : newTargetSpot) {
					    targetSpot = spot;
					    break;
					}
				}
				double t_daughter = targetSpot.getFeature("FRAME");

				targetSpot = son;
				nTarget = 1;
				while (nTarget == 1) {
					Set <Spot> newTargetSpot = neighborIndex.successorsOf(targetSpot);
					nTarget = newTargetSpot.size();
					for(Spot spot : newTargetSpot) {
					    targetSpot = spot;
					    break;
					}
				}
				double t_son = targetSpot.getFeature("FRAME");

				double[] result = new double[] {t_mitosis-t_mother, t_daughter-t_mitosis, t_son-t_mitosis};
				return result;
			
			}
	
	public static Spot[] branchStartAndEnds(
			TrackModel trackmodel,
			Spot spotMitosis
			) {

				//double t_mitosis = spotMitosis.getFeature("FRAME");
				Spot[] result = new Spot[3];
				
				TimeDirectedNeighborIndex neighborIndex  = trackmodel.getDirectedNeighborIndex();
				Set <Spot> predecessors = neighborIndex.predecessorsOf(spotMitosis);
				Set <Spot> successors = neighborIndex.successorsOf(spotMitosis);

				Spot mother = null;
				for (Spot spot : predecessors) {
					mother = spot;
				}
				Spot daughter = null;	Spot son = null;
				int i = 0;
				for (Spot spot : successors) {
					if (i == 0) {
						daughter = spot;
						i++;
					} else {
						son = spot;
					}
				}

				int nSource = 1;
				int nTarget = 1;

				Spot sourceSpot = mother;
				while (nSource == 1) {
					Set <Spot> newSourceSpot = neighborIndex.predecessorsOf(sourceSpot);
					nSource = newSourceSpot.size();
					for(Spot spot : newSourceSpot) {
					    sourceSpot = spot;
					    break;
					}
				}
				result[0] = sourceSpot;
				//double t_mother = sourceSpot.getFeature("FRAME");

				Spot targetSpot = daughter;
				
				while (nTarget == 1) {
					Set <Spot> newTargetSpot = neighborIndex.successorsOf(targetSpot);
					nTarget = newTargetSpot.size();
					for(Spot spot : newTargetSpot) {
					    targetSpot = spot;
					    break;
					}
				}
				result[1] = sourceSpot;
				//double t_daughter = targetSpot.getFeature("FRAME");

				targetSpot = son;
				nTarget = 1;
				while (nTarget == 1) {
					Set <Spot> newTargetSpot = neighborIndex.successorsOf(targetSpot);
					nTarget = newTargetSpot.size();
					for(Spot spot : newTargetSpot) {
					    targetSpot = spot;
					    break;
					}
				}
				result[2] = sourceSpot;
				//double t_son = targetSpot.getFeature("FRAME");

				//double[] result = new double[] {t_mitosis-t_mother, t_daughter-t_mitosis, t_son-t_mitosis};
				return result;
			
			}
	
	public static void mitosisCheckWithRoiManager(
			ImagePlus imp,
			String modelPath
			) {
		
		RoiManager rm = RoiManager.getInstance2();
		if (rm == null) return;
		
		// get cropped image around Spot
		for (int i=0; i<rm.getCount(); i++) {
			rm.select(imp, i);
			ImagePlus impCrop = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), imp.getFrame(), imp.getFrame());
			Instances traceData = WekaUtility.createTrainingData(impCrop, rm.getRoi(i));
			impCrop.close();
			if (WekaUtility.getPredictionResult(modelPath, traceData)) {
				rm.rename(i, rm.getName(i)+" mitosis");
			}
		}
	}
	
	public static boolean mitosisCheckWithTrainedModel(
			Spot spot,
			ImagePlus imp,
			double size,
			String modelPath
			) {
			
			Boolean prediction = false;
			// get cropped image around Spot
			double pixelSize = imp.getCalibration().pixelWidth;
			double x = spot.getFeature("POSITION_X");
			double y = spot.getFeature("POSITION_Y");
			double t = spot.getFeature("FRAME");
			
			OvalRoi roi = new OvalRoi((x-size/2)/pixelSize,(y-size/2)/pixelSize, size/pixelSize, size/pixelSize);
			
			imp.setPositionWithoutUpdate(1, 1, (int) t);
			imp.setRoi(roi, false);
			ImagePlus impCrop = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), (int)t, (int)t );
			imp.deleteRoi();
			roi.setLocation(0, 0);
			Instances traceData = WekaUtility.createTrainingData(impCrop, roi);
			impCrop.close();
			prediction = WekaUtility.getPredictionResult(modelPath, traceData);

			return prediction;
	}
	
	public static double[] getMitosisDetectionThreshold (
			ImagePlus imp,
			int hGEMChannel,
			int dapiChannel,
			double sensitivity	// sensitivity as a value between 0 and 100, with 100 being more sensitive
			) {
			// get size threshold
			double size_thr = (2*sensitivity + 100)/300;	// 0-100 -> 0.33-1
					
			// 0-100 -> 0.5-1.5
			sensitivity = (sensitivity+50)/100;
			
			// sizeThr = (sensitivity+100)/200;
			ImagePlus imphGEM = new Duplicator().run(imp, hGEMChannel, hGEMChannel, 1, imp.getNSlices(), 1, imp.getNFrames());
			StackStatistics stats_hGEM = new StackStatistics(imphGEM);
			double hGEM_mean_thr = stats_hGEM.mean + 2*stats_hGEM.stdDev;
			imphGEM.close();
			ImagePlus impDAPI = new Duplicator().run(imp, dapiChannel, dapiChannel, 1, imp.getNSlices(), 1, imp.getNFrames());
			StackStatistics stats_DAPI = new StackStatistics(impDAPI);
			double DAPI_stdDev_thr = 2*stats_DAPI.stdDev;
			double DAPI_cv_thr = DAPI_stdDev_thr/(3*stats_DAPI.mean);
			impDAPI.close();
			double[] thresholds = new double[] {size_thr, sensitivity*DAPI_stdDev_thr, sensitivity*DAPI_cv_thr, sensitivity*hGEM_mean_thr};
			System.gc();
			return thresholds;
	}
	
	public static boolean mitosisCheckQuick(
			ImagePlus imp,
			Roi roi,
			int hGEMChannel,
			int dapiChannel,
			double[] thresholds
			) {
			double roiMinimumArea = roi.getStatistics().area * thresholds[0];
			imp.setRoi(roi);
			if (roi.getStatistics().area < roiMinimumArea) return false;
			imp.setZ(roi.getZPosition());
			imp.setT(roi.getTPosition());
			imp.setC(dapiChannel);
			ImageStatistics stats_DAPI = imp.getRawStatistics();
			if (stats_DAPI.stdDev < thresholds[1])	return false;
			if (stats_DAPI.stdDev/stats_DAPI.mean < thresholds[2]) return false;
			imp.setC(hGEMChannel);
			ImageStatistics stats_hGEM = imp.getRawStatistics();
			if (stats_hGEM.mean < thresholds[3]) return false;
			return true;	
	}
			
	public static double mitosisCheckQuickTwo(
			ImagePlus imp,
			Roi roi,
			int hGEMChannel,
			int dapiChannel,
			double[] thresholds
			) {
			double mitosisScore = 1;
			imp.setRoi(roi);
			imp.setZ(roi.getZPosition());
			imp.setT(roi.getTPosition());
			imp.setC(dapiChannel);
			ImageStatistics stats_DAPI = imp.getRawStatistics();
			
			if (stats_DAPI.stdDev < thresholds[0])	return 0;
			else mitosisScore *= (stats_DAPI.stdDev/thresholds[0]);
			
			if (stats_DAPI.stdDev/stats_DAPI.mean < thresholds[1]) return 0;
			else mitosisScore *= (stats_DAPI.stdDev/stats_DAPI.mean/thresholds[1]);
			
			imp.setC(hGEMChannel);
			ImageStatistics stats_hGEM = imp.getRawStatistics();
			if (stats_hGEM.mean < thresholds[2]) return 0;
			else mitosisScore *= (stats_hGEM.mean/thresholds[2]);
			
			return mitosisScore;
	}
	
	public static boolean mitosisCheck(
			TrackModel trackmodel,
			Spot spotMitosis,
			double acceptedProb
			) {
				
			//spots =	tm.trackSpots(trackID);
			//edges = tm.trackEdges(trackID);
			//TimeDirectedNeighborIndex 
			TimeDirectedNeighborIndex neighborIndex  = trackmodel.getDirectedNeighborIndex();

			// get mother and daughter spot
			Set <Spot> predecessors = neighborIndex.predecessorsOf(spotMitosis);
			Set <Spot> successors = neighborIndex.successorsOf(spotMitosis);
			int nSource = predecessors.size();
			int nTarget = successors.size();
			if ((nSource!=1) || (nTarget!=2)) {
				IJ.log("spot ID: " + String.valueOf(spotMitosis.ID()));
				IJ.log("nSource: " + String.valueOf(nSource));
				IJ.log("nTarget: " + String.valueOf(nTarget));
				for (Spot spot : predecessors) {
					IJ.log("Source ID: " + String.valueOf(spot.ID()));
				}
				IJ.log("mitosis check error with wrong predecessor and successor number.");
				return false;
			}

			Spot mother = null;
			for (Spot spot : predecessors) {
				mother = spot;
				break;
			}
			Spot daughter = null;	Spot son = null;
			int i = 0;
			for (Spot spot : successors) {
				if (i == 0) {
					daughter = spot;
					i++;
				} else {
					son = spot;
				}
			}

			//	sizeCheck
			double sizeProbWeight = 0.35;
			double sizeMother = mother.getFeature("ESTIMATED_DIAMETER");
			double sizeDaughter = daughter.getFeature("ESTIMATED_DIAMETER");
			double sizeSon = son.getFeature("ESTIMATED_DIAMETER");
			double sizeProb = 0;
			/*
			if (sizeMother > sizeDaughter) {
				sizeProb += 0.5; 
			}
			if (sizeMother > sizeSon) {
				sizeProb += 0.5; 
			}
			*/
			double sProb1 = (Math.max(0, (1-sizeDaughter/sizeMother)))*0.5;
			double sProb2 = (Math.max(0, (1-sizeSon/sizeMother)))*0.5;
			sizeProb = sProb1 + sProb2;
			//IJ.log("mitosis check, size prob: " + String.valueOf(sizeProb));
			
			//	morphologyCheck
			double morphProbWeight = 0;
			double morphProb = 0;
			
			//	colorCheck
			double colorProbWeight = 0.65;
			double redMother = mother.getFeature("MEAN_INTENSITY01");
			//double greenMother = mother.getFeature("MEAN_INTENSITY02");
			double blueMother = mother.getFeature("MEAN_INTENSITY03");
			double redDaughter = daughter.getFeature("MEAN_INTENSITY01");
			//double greenDaughter = daughter.getFeature("MEAN_INTENSITY02");
			double blueDaughter = daughter.getFeature("MEAN_INTENSITY03");
			double redSon = son.getFeature("MEAN_INTENSITY01");
			//double greenSon = son.getFeature("MEAN_INTENSITY02");
			double blueSon = son.getFeature("MEAN_INTENSITY03");
			double colorProb = 0;

			/*
			if ((blueMother*2) < blueDaughter)	colorProb += 0.25;
			if ((blueMother*2) < blueSon)	colorProb += 0.25;
			if (redMother > (redDaughter*1.5)) colorProb += 0.25;
			if (redMother > (redSon*1.5)) colorProb += 0.25;
			*/
			double cProb1 = (Math.max(0,(1-blueMother/blueDaughter)))*0.15;
			double cProb2 = (Math.max(0,(1-blueMother/blueSon)))*0.15;
			double cProb3 = (Math.max(0,(1-redDaughter/redMother)))*0.35;
			double cProb4 = (Math.max(0,(1-redSon/redMother)))*0.35;
			//IJ.log("mitosis check, color prob: " + String.valueOf(colorProb));
			colorProb = cProb1 + cProb2 + cProb3 + cProb4;
			//IJ.log("mitosis check, color prob: " + String.valueOf(colorProb));
			
			double mitosisProbability = sizeProbWeight*sizeProb + morphProbWeight*morphProb + colorProbWeight*colorProb;
			//IJ.log("mitosis prob: " + String.valueOf(mitosisProbability));
			return (mitosisProbability>acceptedProb);
		}
}
