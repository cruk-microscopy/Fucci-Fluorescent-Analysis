package uk.ac.cam.cruk.fglab;

//import de.csbdresden.stardist.StarDist2D;
//import de.csbdresden.stardist.StarDist2DModel;

import java.awt.Color;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;



public class StarDistUtility implements PlugIn {

	private ImagePlus inputImp;
	private int targetChannel;
	
	private final String inputName = "StarDist2D input image";

	private Roi[] sdRois;
	private int posC = 0;
	private int posZ = 0;
	private int posT = 0;
	

	public StarDistUtility () {
		this.inputImp = WindowManager.getCurrentImage();
		this.targetChannel = 1;
	}
	public StarDistUtility (ImagePlus imp) {
		this.inputImp = imp;
		this.targetChannel = 1;
	}
	public StarDistUtility (ImagePlus imp, int channel) {
		this.inputImp = imp;
		this.targetChannel = channel;
	}
	public Roi[] getRois () {
		return this.sdRois;
	}

	public void main(final String... args) throws Exception {}
	
	protected static void getRois (ImagePlus imp, int channel, Color roiColor) {
		//ImagePlus labelImp = getLabelImage(imp, channel);
		//Roi[] rois = labelToRois (labelImp, roiColor);
		//labelImp.close(); System.gc();
		//return combineRois(rois);
	}
	
	protected void setRoiPosition (int posC, int posZ, int posT) {
		this.posC = posC;
		this.posZ = posZ;
		this.posT = posT;
	}
	
	protected Roi[] getRoisFromManager(Color color) {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null) return null;
		Roi[] rois = rm.getRoisAsArray();
		for (int i=0; i<rois.length; i++) {
			//rois[i] = new ShapeRoi(rois[i]);
			rois[i].setPosition(
				this.posC==-1 ? rois[i].getCPosition() : this.posC, 
				this.posZ==-1 ? rois[i].getZPosition() : this.posZ, 
				this.posT==-1 ? rois[i].getTPosition() : this.posT);
			rois[i].setStrokeColor(color);
		}
		return rois;
	}
	
	protected static Roi combineRois (Roi[] rois) {
		if (rois==null) return null;
		if (rois.length==1) return rois[0];
		int idx = 0;
		for (idx=0; idx<rois.length; idx++) {
			if (rois[idx].isArea()) break;
		}
		Roi firstRoi = new ShapeRoi(rois[idx]);
		for (int i=idx+1; i<rois.length; i++) {
			if (!rois[i].isArea()) continue;
			firstRoi = ((ShapeRoi) firstRoi).or(new ShapeRoi(rois[i]));
		}
		return firstRoi;
	}

	@Override
	public void run(String arg) {
		// fetch input image;
		if (inputImp==null) return;
		
		// fetch and prepare RoiManager
		RoiManager rm = ROIUtility.prepareManager(true);
		
		// prepare ROI channel from detection panel:
		if (targetChannel > inputImp.getNChannels()) targetChannel = 1;
		
		// get channel 2D image
		Roi roi = inputImp.getRoi(); inputImp.deleteRoi();
		ImagePlus impDup = new Duplicator().run(inputImp, targetChannel, targetChannel, inputImp.getZ(), inputImp.getZ(), 1, inputImp.getNFrames());
		inputImp.setRoi(roi);
		impDup.setTitle(inputName);
		impDup.show(); impDup.getWindow().setVisible(false);
		
		IJ.run("Command From Macro", 
				"command=[de.csbdresden.stardist.StarDist2D], "
				+ "args=['input':'"+inputName+"', "
				+ "'modelChoice':'Versatile (fluorescent nuclei)', "
				+ "'normalizeInput':'true', "
				+ "'percentileBottom':'0.30000000000000004', 'percentileTop':'99.8', "
				+ "'probThresh':'0.09999999999999999', 'nmsThresh':'0.25', "
				+ "'outputType':'ROI Manager', "
				+ "'nTiles':'1', 'excludeBoundary':'2', "
				+ "'roiPosition':'Hyperstack', "
				+ "'verbose':'false', "
				+ "'showCsbdeepProgress':'false', "
				+ "'showProbAndDist':'false'], "
				+ "process=[false]");
		
		sdRois = getRoisFromManager (Color.YELLOW);
		
		impDup.close();
		ROIUtility.restoreManager();
		System.gc();
		
	}


}