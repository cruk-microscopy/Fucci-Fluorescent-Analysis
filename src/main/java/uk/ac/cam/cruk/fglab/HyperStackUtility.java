package uk.ac.cam.cruk.fglab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.RoiScaler;
import ij.plugin.Zoom;
import ij.process.LUT;
import ij.process.StackStatistics;

public class HyperStackUtility {
	
	
	public static boolean addSlice (
		ImagePlus source,
		ImagePlus target,
		int dim,	// 1-based dimension XYCZT
		int sourceIdx,
		int targetIdx,	// insert at (before current) targetIdx, if targetIdx=0, add slice in the end
		boolean keepSliceInSource
		) {
		/* Control the input parameters:
		 * 	both source and target image need to be exist;
		 *  source need to contain at least 1 slice;
		 *  move index need to be inside the range of source slices
		 */
		if (source==null || target==null || source.getNSlices()==0) return false;
		// check source and target size, and replace index
		int[] dim1 = source.getDimensions();
		int[] dim2 = target.getDimensions();
		if (dim<3 || dim>5 || sourceIdx<1 || sourceIdx>dim1[dim-1] || targetIdx<0 || targetIdx>dim2[dim-1])	return false;	// dimension out of range
		boolean resize = (dim1[0]!=dim2[0] || dim2[1]!=dim2[1]);
		for (int i=3; i<=5; i++) {
			if (i == dim) continue;
			if (dim1[i-1] != dim2[i-1]) return false;	// dimension mismatch between source and target
		}
		
		//	Prepare the substack (slice) image to be copied over
		int numC = dim1[2]; int numZ = dim1[3]; int numT = dim1[4];
		int[] range = {1, numC, 1, numZ, 1, numT};
		range[2*dim-6] = sourceIdx;	range[2*dim-5] = sourceIdx;					
		ImagePlus sliceImp = new Duplicator().run(source, range[0], range[1], range[2], range[3], range[4], range[5]);
		int nSlices = sliceImp.getStackSize();
		if (resize)
			IJ.run(sliceImp, "Size...", "width=["+target.getWidth()+"] height=["+target.getHeight()+"] average interpolation=Bicubic");
		// check if the slice has already exist in the target
		int dupSlice = checkDuplicate(sliceImp, target, dim, 0);
		if (dupSlice!=0) {
			if (dim==3) target.setC(dupSlice);
			else if (dim==4) target.setZ(dupSlice);
			else if (dim==5) target.setT(dupSlice);
			System.out.println("Sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			return false;
		}
		
		// add slice to target stack, update target overlay, repaint window
		ImageStack sourceStack = source.getStack();
		ImageStack targetStack = target.getStack();
		
		int[] targetHyperStackIndex = {1, 1, 1};
		targetHyperStackIndex[dim-3] = targetIdx==0 ? dim2[dim-1]+1 : targetIdx;
		int nStart = target.getStackIndex(
			targetHyperStackIndex[0],
			targetHyperStackIndex[1],
			targetHyperStackIndex[2]) - 1;
			
		for (int i=nSlices; i>=1; i--) {
			if (targetIdx==0)
				targetStack.addSlice(sliceImp.getStack().getSliceLabel(nSlices-i+1), sliceImp.getStack().getProcessor(nSlices-i+1));
			else
				targetStack.addSlice(sliceImp.getStack().getSliceLabel(i), sliceImp.getStack().getProcessor(i), nStart);
		}
		dim2[dim-1] += 1;
    	//after added slice to imagestack, shift overlay and copy overlay from source
    	Overlay tOverlay = target.getOverlay();
    	//Point targetWindowLocation = target.getWindow().getLocation();
    	//target.hide();
    	target.setStack(targetStack, dim2[2], dim2[3], dim2[4]);
    	target.setOverlay(tOverlay);
    	if (targetIdx>0) shiftOverlay(target, dim, targetIdx, 1);
    	copyOverlay(source, target, dim, sourceIdx, targetIdx==0 ? dim2[dim-1] : targetIdx);
    	target.setPosition(targetHyperStackIndex[0], targetHyperStackIndex[1], targetHyperStackIndex[2]);
    	//target.show();
    	//target.getWindow().setLocation(targetWindowLocation);
    	target.changes = true;
    	target.getWindow().updateImage(target);
    	target.getWindow().pack();
    	WindowManager.setWindow(target.getWindow());
		new Zoom().run("100%");
		target.setDisplayMode(IJ.COLOR);
		target.setDisplayMode(IJ.COMPOSITE);
    	/* if keep slice, finised 
    	 * otherwise delete the slice from source, 
    	 * update the source overlay, repaint window
    	 */ 
    	if (!keepSliceInSource) {
    		if (dim1[dim-1]==1) {
    			source.changes = false;
    			source.close();
    			System.gc();
    		} else {
    			if (!deleteSlice(source, dim, sourceIdx))
    				System.out.println("  Could not delete slice " + sourceIdx + " in source image " + source.getTitle());
    			WindowManager.setWindow(source.getWindow());
				new Zoom().run("100%");
				source.setDisplayMode(IJ.COLOR);
				source.setDisplayMode(IJ.COMPOSITE);
    		}
    	}
		return true;	
	}

	public static boolean replaceSlice(
		ImagePlus source,
		ImagePlus target,
		int dim,	// 1-based dimension XYCZT
		int sourceIdx,	// 1-based slice index, currently using Z index
		int targetIdx,	// 1-based slice index, currently using Z index
		boolean keepSliceInSource
		) {
		/* Control the input parameters:
		 * 	both source and target image need to be exist;
		 *  source need to contain at least 1 slice;
		 *  move index need to be inside the range of source slices
		 */
		// check source and target exist
		if (source==null || target==null || source.getNSlices()==0) return false;
		// check source and target size, and replace index
		int[] dim1 = source.getDimensions();
		int[] dim2 = target.getDimensions();
		if (dim<3 || dim>5 || dim1[dim-1]<sourceIdx || dim2[dim-1]<targetIdx)	return false;	// dimension out of range

		boolean resize = (dim1[0]!=dim2[0] || dim2[1]!=dim2[1]);
		for (int i=3; i<=5; i++) {
			if (i == dim) continue;
			if (dim1[i-1] != dim2[i-1]) return false;	// dimension mismatch between source and target
		}	
		int numC = dim1[2]; int numZ = dim1[3]; int numT = dim1[4];
		
		// add slice to target with label and overlay, repaint window
		int[] range = {1, numC, 1, numZ, 1, numT};
		range[2*dim-6] = sourceIdx;	range[2*dim-5] = sourceIdx;					
		ImagePlus sliceImp = new Duplicator().run(source, range[0], range[1], range[2], range[3], range[4], range[5]);
		int nSlices = sliceImp.getStackSize();
		
		if (resize) {
			IJ.run(sliceImp, "Size...", "width=["+target.getWidth()+"] height=["+target.getHeight()+"] average interpolation=Bicubic");
		}
		
		// check if the slice has already exist in the target
		int dupSlice = checkDuplicate(sliceImp, target, dim, 0);
		if (dupSlice!=0) {
			if (dim==3) target.setC(dupSlice);
			else if (dim==4) target.setZ(dupSlice);
			else if (dim==5) target.setT(dupSlice);
			System.out.println("Sample already exist in " + target.getTitle() + " at " + String.valueOf(dupSlice));
			return false;
		}
		
		// add slice to target stack, update target overlay, repaint window
		ImageStack sourceStack = source.getStack();
		ImageStack targetStack = target.getStack();

		int[] targetHyperStackIndex = {1, 1, 1};
		targetHyperStackIndex[dim-3] = targetIdx;
		int nEnd = target.getStackIndex(
			targetHyperStackIndex[0],
			targetHyperStackIndex[1],
			targetHyperStackIndex[2]);
		
		for (int i=1; i<=nSlices; i++) {
			targetStack.setProcessor(sliceImp.getStack().getProcessor(i), nEnd+i-1);
			// update slice label
			targetStack.setSliceLabel(sliceImp.getStack().getSliceLabel(i), targetIdx);
		}
    	target.setStack(targetStack);
    	copyOverlay(source, target, dim, sourceIdx, targetIdx);	// only copy(replace) to target without updating the source
    	target.setPosition(targetHyperStackIndex[0], targetHyperStackIndex[1], targetHyperStackIndex[2]);
    	target.changes = true;
    	target.getWindow().updateImage(target);
    	target.getWindow().pack();
    	WindowManager.setWindow(target.getWindow());
		new Zoom().run("100%");
		target.setDisplayMode(IJ.COLOR);
		target.setDisplayMode(IJ.COMPOSITE);
    	
		/* if keep slice, finised 
    	 * otherwise delete the slice from source, 
    	 * update the source overlay, repaint window
    	 */ 
    	if (!keepSliceInSource) {
    		if (dim1[dim-1]==1) {
    			source.changes = false;
    			source.close();
    			System.gc();
    		} else {
    			if (!deleteSlice(source, dim, sourceIdx))
    				System.out.println("  Could not delete slice " + sourceIdx + " in source image " + source.getTitle());
    			WindowManager.setWindow(source.getWindow());
				new Zoom().run("100%");
				source.setDisplayMode(IJ.COLOR);
				source.setDisplayMode(IJ.COMPOSITE);
    		}
    	}	
		return true;
	}

	
	public static int checkDuplicate(
		ImagePlus impSlice, 
		ImagePlus impStack,
		int dim,	// delete dimension, 1-based, XYCZT
		int sliceNum	// if sliceNum is 0, compare all stack slices, otherwise compare a specific slice at sliceNum in stack				
		) {				// if sliceNum is -1, do self comparison
		if (dim<3 || dim>5) return 0;
		int[] dimensions = impStack.getDimensions();	//[X, Y, C, Z, T]
		int nSlices = dimensions[dim-1];
		int[] duplicatorParam = {1, dimensions[2], 1, dimensions[3], 1, dimensions[4]};
		ImagePlus impSlice2 = null;
		
		if (sliceNum == -1) {	// self comparison
			ArrayList<Integer> sliceIdx = new ArrayList<Integer>();
			for (int i=0; i<nSlices; i++)
				sliceIdx.add(i+1);
			ArrayList<Integer> deleteIdx = new ArrayList<Integer>();			
			Iterator<Integer> iter = sliceIdx.iterator();
			while (iter.hasNext()) {
				Integer idx = iter.next();
				if (deleteIdx.contains(idx)) continue;	// idx already in remove list, continue
				iter.remove();
				duplicatorParam[dim*2-6] = idx;	duplicatorParam[dim*2-5] = idx;
				impSlice = new Duplicator().run(impStack, 
					duplicatorParam[0], duplicatorParam[1],
					duplicatorParam[2], duplicatorParam[3],
					duplicatorParam[4], duplicatorParam[5]);
				for (Integer idx2 : sliceIdx) {	// check through all the rest image
					if (deleteIdx.contains(idx2)) continue;
					duplicatorParam[dim*2-6] = idx2;	duplicatorParam[dim*2-5] = idx2;
					impSlice2 = new Duplicator().run(impStack, 
						duplicatorParam[0], duplicatorParam[1],
						duplicatorParam[2], duplicatorParam[3],
						duplicatorParam[4], duplicatorParam[5]);
					if (checkIdentical2(impSlice, impSlice2))	// duplicate found
						deleteIdx.add(idx2);
				}
			}
			Collections.sort(deleteIdx); Collections.reverse(deleteIdx);
			for (Integer del : deleteIdx)
				deleteSlice(impStack, dim, del);
			return 1;
		}
		if (sliceNum == 0) {	// compare all stack
			for (int idx=0; idx<nSlices; idx++) {
				duplicatorParam[dim*2-6] = idx+1;	duplicatorParam[dim*2-5] = idx+1;
				impSlice2 = new Duplicator().run(impStack, 
					duplicatorParam[0], duplicatorParam[1],
					duplicatorParam[2], duplicatorParam[3],
					duplicatorParam[4], duplicatorParam[5]);
				if (checkIdentical2(impSlice, impSlice2)) return (idx+1);
			}
			return 0;
		}		
		if (sliceNum>nSlices) return 0;
		
		duplicatorParam[dim*2-6] = sliceNum;	duplicatorParam[dim*2-5] = sliceNum;
		impSlice2 = new Duplicator().run(impStack, 
			duplicatorParam[0], duplicatorParam[1],
			duplicatorParam[2], duplicatorParam[3],
			duplicatorParam[4], duplicatorParam[5]);
		if (checkIdentical2(impSlice, impSlice2)) return (sliceNum);
				
		return 0;	// slice not exist in stack
	}

	public static boolean checkIdentical2 (
		ImagePlus imp1,	// could be stack
		ImagePlus imp2	//
		) {
		// check type
		if (imp1.getType()!=imp2.getType()) return false;
		// check dimension
		int[] dim1 = imp1.getDimensions();
		int[] dim2 = imp2.getDimensions();
		if (!Arrays.equals(dim1, dim2))	 return false;
		// check statistics mean and stdDev
		StackStatistics stat1 = new StackStatistics(imp1);
		StackStatistics stat2 = new StackStatistics(imp2);
		if (stat1.mean != stat2.mean) return false;
		if (stat1.stdDev != stat2.stdDev) return false;
		// check histogram
		double[] hist1 = stat1.histogram();
		double[] hist2 = stat2.histogram();
		if (!Arrays.equals(hist1, hist2)) return false;
		// check pixel
		int numSlices = imp1.getStackSize();
		for (int i=0; i<numSlices; i++) {
			imp1.setSliceWithoutUpdate(i+1);
			imp2.setSliceWithoutUpdate(i+1);
			float[][] pixelArray1 = imp1.getProcessor().getFloatArray();
			float[][] pixelArray2 = imp2.getProcessor().getFloatArray();
			int nCol = pixelArray1.length;
			for (int c=0; c<nCol; c++) {
				if (!Arrays.equals(pixelArray1[c], pixelArray2[c])) return false;
			}
		}
		return true;
	}

	public static int checkIdentical (
		ImagePlus imp1,	// could be stack
		ImagePlus imp2,	// 
		int dim	// compared dimension, 1-based XYCZT
		) {
		// check type
		if (imp1.getType()!=imp2.getType()) return 0;
		// check dimension
		int[] dim1 = imp1.getDimensions();
		int[] dim2 = imp2.getDimensions();
		for (int i=3; i<=5; i++) {
			if (i == dim) continue;	// don't compare size of the compared dimension
			if (dim1[i-1] != dim2[i-1]) return 0;
		}
		// check size
		boolean resize = (dim1[0]!=dim2[0] || dim1[1]!=dim2[1]);
		IJ.run(imp2, "Size...", "width=["+dim1[0]+"] height=["+dim1[1]+"] average interpolation=Bicubic");

		int nSlices = dim1[dim-1];
		int[] duplicatorParam = new int[6];
		for (int i=0; i<3; i++) {
			duplicatorParam[i*2] = 1;
			duplicatorParam[i*2+1] = dim1[i+2];
		}
		
		for (int i=1; i<=nSlices; i++) {
			duplicatorParam[dim*2-6] = i;	duplicatorParam[dim*2-5] = i;
			ImagePlus sliceImp = new Duplicator().run(imp1, 
				duplicatorParam[0], duplicatorParam[1],
				duplicatorParam[2], duplicatorParam[3],
				duplicatorParam[4], duplicatorParam[5]);
			// check statistics
			StackStatistics stat1 = new StackStatistics(sliceImp);
			StackStatistics stat2 = new StackStatistics(imp2);
			if (stat1.mean != stat2.mean)	continue;
			if (stat1.stdDev != stat2.stdDev)	continue;
			double[] hist1 = stat1.histogram();
			double[] hist2 = stat2.histogram();
			if (!Arrays.equals(hist1, hist2)) continue;
			
			// check pixel
			int nSubSlices = imp2.getStackSize();
			for (int j=0; j<nSubSlices; j++) {
				sliceImp.setSliceWithoutUpdate(j+1);
				imp2.setSliceWithoutUpdate(j+1);
				float[][] pixelArray1 = sliceImp.getProcessor().getFloatArray();
				float[][] pixelArray2 = imp2.getProcessor().getFloatArray();
				int nCol = pixelArray1.length;
				for (int c=0; c<nCol; c++) {
					if (Arrays.equals(pixelArray1[c], pixelArray2[c])) return i;
				}
			}
		}
		return 0;
	}
	
	public static boolean deleteSlice(
			ImagePlus imp, 
			int dim, // dimension 1 based: XYCZT
			int index
			) {	
		int[] dimension = imp.getDimensions(); //XYCZT
		if (dimension[dim-1]<index) return false;
		if (dimension[dim-1] == 1) {
			imp.changes = true;
			imp.close();
			System.gc();
			return true;
		}
		
		int posC = imp.getC();
		int posZ = imp.getZ();
		int posT = imp.getT();
		if (dim==3) posC = index==1 ? 1 : (index-1);
		if (dim==4) posZ = index==1 ? 1 : (index-1);
		if (dim==5) posT = index==1 ? 1 : (index-1);
		int numSlices = imp.getStackSize();
		boolean hyperStack = imp.isHyperStack();

		dimension[dim-1] -= 1;

		ImageStack oldStack = imp.getStack();
		ImageStack newStack = oldStack.duplicate();
		
		Overlay oldOverlay = imp.getOverlay();
		Overlay newOverlay = new Overlay();
		boolean changeOverlay = (dim==4 && oldOverlay!=null);
		if (changeOverlay) {
			Roi[] overlayRois = oldOverlay.toArray();
			for (int i=0; i<overlayRois.length; i++) {
				if (overlayRois[i].getZPosition() < index) {
					newOverlay.add((Roi) overlayRois[i].clone());
				} else if (overlayRois[i].getZPosition() > index) {
					Roi newRoi = (Roi) overlayRois[i].clone();
					if (hyperStack) {
						newRoi.setPosition(
						overlayRois[i].getCPosition(), 
						overlayRois[i].getZPosition()-1, 
						overlayRois[i].getTPosition());
					}
					else {
						newRoi.setPosition(overlayRois[i].getZPosition()-1);
					}
					newOverlay.add(newRoi);
				} else {
					continue;
				}
			}
		}

		for (int i=numSlices; i>=1; i--) {
			int[] hyperStackPosition = imp.convertIndexToPosition(i);
			if (hyperStackPosition[dim-3] == index) {
				newStack.deleteSlice(i);
			}
		}

		imp.setStack(newStack, dimension[2], dimension[3], dimension[4]);
		if (changeOverlay) imp.setOverlay(newOverlay);

		imp.setPosition(posC, posZ, posT);
		imp.setDisplayMode(IJ.COLOR);
		imp.setDisplayMode(IJ.COMPOSITE);
		return true;
	}
	
	public static void swapSlices (
		ImagePlus imp,
		int dim,	// 1-based dimension XYCZT
		ArrayList<Integer> newIndex
		) {
		LUT[] oriLUT = imp.getLuts();
		int[] dimensions = imp.getDimensions();
		if (dimensions[dim-1] != newIndex.size()) return;
		int numSlices = imp.getStackSize();
		boolean hyperStack = imp.isHyperStack();
		
		ImageStack oldStack = imp.getStack();
		ImageStack newStack = new ImageStack(imp.getWidth(), imp.getHeight(), numSlices);
		
		Overlay oldOverlay = imp.getOverlay();
		Overlay newOverlay = new Overlay();
		boolean changeOverlay = (dim==4 && oldOverlay!=null);
		if (dim==4 && oldOverlay!=null) {
			newOverlay = oldOverlay.duplicate();
			Roi[] overlayRois = oldOverlay.toArray();
			for (int i=0; i<overlayRois.length; i++) {
				int oldZ = overlayRois[i].getZPosition();
				int newZ = newIndex.indexOf(oldZ)+1;
				Roi newRoi = (Roi) overlayRois[i].clone();
				if (hyperStack)
					newRoi.setPosition(overlayRois[i].getCPosition(), newZ, overlayRois[i].getTPosition());
				else
					newRoi.setPosition(newZ);
				newOverlay.set(newRoi, i);
			}
		}
		for (int i=0; i<numSlices; i++) {
			int[] hyperstackPosition = imp.convertIndexToPosition(i+1);
			hyperstackPosition[dim-3] = newIndex.indexOf(hyperstackPosition[dim-3])+1;
			int newStackIndex = imp.getStackIndex(
				hyperstackPosition[0],
				hyperstackPosition[1],
				hyperstackPosition[2]);
			newStack.setProcessor(oldStack.getProcessor(i+1), newStackIndex);
			newStack.setSliceLabel(oldStack.getSliceLabel(i+1), newStackIndex);
		}
		//imp.setStack(newStack);
		imp.setStack(newStack, dimensions[2], dimensions[3], dimensions[4]);
		imp.setLut(oriLUT[0]);
		if (changeOverlay) imp.setOverlay(newOverlay);
		//imp.setDisplayMode(IJ.COLOR);
		//imp.setDisplayMode(IJ.COMPOSITE);		
	}

	
	public static void swapSlices(
			ImagePlus imp, 
			int dim,	// 1-based dimension XYCZT 
			int index1, // 1-based dimension index
			int index2
			) {
		if (index1==index2) return;
		int[] dimension = imp.getDimensions();
		if (dimension[dim-1]<index1 || dimension[dim-1]<index2) return;
		
		int numSlices = imp.getStackSize();
		int numC = dimension[2];
		int numZ = dimension[3];
		int numT = dimension[4];

		ImageStack oldStack = imp.getStack();
		ImageStack newStack = new ImageStack(imp.getWidth(), imp.getHeight());
	
		Overlay oldOverlay = imp.getOverlay();
		Overlay newOverlay = new Overlay();
		boolean changeOverlay = (dim==4 && oldOverlay!=null);
		if (changeOverlay) {
			newOverlay = oldOverlay.duplicate();
			Roi[] overlayRois = oldOverlay.toArray();
			for (int i=0; i<overlayRois.length; i++) {
				if (overlayRois[i].getZPosition() == index1) {
					Roi newRoi = (Roi) overlayRois[i].clone();
					newRoi.setPosition(overlayRois[i].getCPosition(), index2, overlayRois[i].getTPosition());
					newOverlay.set(newRoi, i);
				} else if (overlayRois[i].getZPosition() == index2) {
					Roi newRoi = (Roi) overlayRois[i].clone();
					newRoi.setPosition(overlayRois[i].getCPosition(), index1, overlayRois[i].getTPosition());
					newOverlay.set(newRoi, i);
				}
			}
		}
		ArrayList<Integer> oriIndex = new ArrayList<Integer>();
		int[] newSliceIndex = new int[numC*numZ*numT];

		for (int i=1; i<=numSlices; i++) {
			int[] hyperStackPosition = imp.convertIndexToPosition(i);
			if (hyperStackPosition[dim-3] == index1) hyperStackPosition[dim-3] = index2;
			else if (hyperStackPosition[dim-3] == index2) hyperStackPosition[dim-3] = index1;
			newSliceIndex[i-1] = imp.getStackIndex(
				hyperStackPosition[0],
				hyperStackPosition[1],
				hyperStackPosition[2]
			);
		}
		for (int i=0; i<numSlices; i++) {
			newStack.addSlice(
						oldStack.getSliceLabel(newSliceIndex[i]),
						oldStack.getProcessor(newSliceIndex[i]) 
						);
		}
		imp.setStack(newStack, numC, numZ, numT);
		if (changeOverlay) imp.setOverlay(newOverlay);
		imp.setDisplayMode(IJ.COLOR);
		imp.setDisplayMode(IJ.COMPOSITE);
	}


	public static void copyOverlay (	// only copy(replace) to target without updating the source
			ImagePlus source,
			ImagePlus target,
			int dim,	// 1-based dimension XYCZT
			int sourceIdx,	// 1-based index
			int targetIdx	// 1-based index
			) {
		if (source==null || target==null)	return; // no source or target image
		
		int[] dim1 = source.getDimensions();
		int[] dim2 = target.getDimensions();
		if (dim<3 || dim>5 || dim1[dim-1]<sourceIdx || dim2[dim-1]<targetIdx)	return;	// dimension out of range

		boolean resize = (dim1[0]!=dim2[0] || dim2[1]!=dim2[1]);
		for (int i=3; i<=5; i++) {
			if (i == dim) continue;
			if (dim1[i-1] != dim2[i-1]) return;	// dimension mismatch between source and target
		}
				
		int numC = dim1[2]; int numZ = dim1[3]; int numT = dim1[4];
		
		Overlay sourceOverlay = source.getOverlay()==null ? null : source.getOverlay().duplicate();
		Overlay targetOverlay = target.getOverlay()==null ? null : target.getOverlay().duplicate();
		
		if (sourceOverlay==null)	return; // no overlay in source
		if (targetOverlay==null) {	// prepare target overlay, remove the target index
			targetOverlay = new Overlay();
		} else {
			Roi[] targetOverlayRois = targetOverlay.toArray();
			for (Roi tRoi : targetOverlayRois) {
				if (dim==3 && tRoi.getCPosition()==targetIdx)
					targetOverlay.remove(tRoi);
				if (dim==4 && tRoi.getZPosition()==targetIdx)
					targetOverlay.remove(tRoi);
				if (dim==5 && tRoi.getTPosition()==targetIdx)
					targetOverlay.remove(tRoi);
			}
		}
		
		Overlay sliceOverlay = new Overlay();
		sliceOverlay = sourceOverlay.duplicate();
		if (dim == 3) {
			sliceOverlay.crop(sourceIdx, sourceIdx, 1, numZ, 1, numT);
		} else if (dim == 4) {
			sliceOverlay.crop(1, numC, sourceIdx, sourceIdx, 1, numT);
		} else if (dim == 5) {
			sliceOverlay.crop(1, numC, 1, numZ, sourceIdx, sourceIdx);
		} else return;
		
		Roi[] overlayRois = sliceOverlay.toArray();
		for (Roi overlayRoi : overlayRois) {
			if (resize) {
				overlayRoi = RoiScaler.scale(overlayRoi, (double)dim2[0]/(double)dim1[0], (double)dim2[1]/(double)dim1[1], true);
				overlayRoi.setLocation(
						(double)((dim2[0]-overlayRoi.getBounds().width)/2), 
						(double)((dim2[1]-overlayRoi.getBounds().height)/2));  // center current ROI
			}
			if (source.isHyperStack()) {
				int[] hyperStackPosition = {0, 0, 0};
				hyperStackPosition[dim-3] = targetIdx;
				overlayRoi.setPosition(
					hyperStackPosition[0], 
					hyperStackPosition[1], 
					hyperStackPosition[2]);	
			}
			else
				overlayRoi.setPosition(targetIdx);
			targetOverlay.add(overlayRoi);
		}
		target.setOverlay(targetOverlay);
	}

	public static void shiftOverlay(
			ImagePlus imp, 
			int dim,
			int beginIdx,	// 1-based stack dimension index for overlay shift
			int increment
			) {
		if (imp==null || imp.getOverlay()==null) return;
		int[] dimensions = imp.getDimensions();
		int numC = dimensions[2];
		int numZ = dimensions[3];
		int numT = dimensions[4];
		int[] incre = {0, 0, 0};
		incre[dim-3] += increment;
		Overlay oldOverlay = imp.getOverlay();
		Roi[] overlayRois = oldOverlay.toArray();
		Overlay newOverlay = new Overlay();
		
		for (Roi overlayRoi : overlayRois) {
			int posC = overlayRoi.getCPosition();
			int posZ = overlayRoi.getZPosition();
			int posT = overlayRoi.getTPosition();

			if (dim==3 && posC>=beginIdx) posC += incre[0];
			if (dim==4 && posZ>=beginIdx) posZ += incre[1];
			if (dim==5 && posT>=beginIdx) posT += incre[2];
			
			if (posC<0 || posC>numC || posZ<0 || posZ>numZ || posT<0 || posT>numT)
				continue;
			if (dim==3 && posC==0) continue;
			if (dim==4 && posZ==0) continue;
			if (dim==5 && posT==0) continue;	
			overlayRoi.setPosition(posC, posZ, posT);
			newOverlay.add(overlayRoi);
		}
		imp.setOverlay(newOverlay);
	}
	

}
