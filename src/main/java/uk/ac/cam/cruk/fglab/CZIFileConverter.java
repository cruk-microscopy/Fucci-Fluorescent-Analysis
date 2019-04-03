package uk.ac.cam.cruk.fglab;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.scijava.prefs.DefaultPrefService;

import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.plugin.ChannelSplitter;
import ij.plugin.HyperStackConverter;
import ij.plugin.PlugIn;
import ij.plugin.RGBStackMerge;
import ij.process.ImageProcessor;

import loci.common.services.ServiceException;
import loci.formats.ChannelSeparator;
import loci.formats.FormatException;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.services.OMEXMLServiceImpl;
import loci.plugins.util.ImageProcessorReader;
import loci.plugins.util.LociPrefs;
import ome.units.UNITS;

public class CZIFileConverter implements PlugIn {

	protected static String rootDirPath = IJ.getDirectory("home");
	protected static boolean doSubDir = false;
	protected static boolean doBackgroundSubtraction = true;
	protected static double bgRadius = 50;
	protected static String saveDirPath = IJ.getDirectory("temp");
	protected static boolean reproduceDirStructure = false;
	
	public static boolean cziConverterDialog() {
		
		GenericDialogPlus gd = new GenericDialogPlus("Extract tiff stacks from CZI file");

		gd.addDirectoryOrFileField("Select folder containing the files need to be converted", rootDirPath);
		gd.addCheckbox("Check through sub-folders", doSubDir);
		gd.addCheckbox("Correct background", doBackgroundSubtraction);
		gd.addNumericField("background subtraction radius:", bgRadius, 0, 3, "pixels");
		gd.addDirectoryOrFileField("Select folder to save converted tiff stacks", saveDirPath);
		gd.addCheckbox("Reproduce source folder structure", reproduceDirStructure);
		
		gd.showDialog();
		
		rootDirPath = gd.getNextString();
		doSubDir = gd.getNextBoolean();
		doBackgroundSubtraction = gd.getNextBoolean();
		bgRadius = gd.getNextNumber();
		saveDirPath = gd.getNextString();
		reproduceDirStructure = gd.getNextBoolean();
		
		if (gd.wasCanceled())	return false;
		
		if (saveDirPath == null) {
			saveDirPath = rootDirPath + File.separator + "TIFF_stacks";
		}
		
		File saveDir = new File(saveDirPath);
		// if the directory does not exist, create it
		if (!saveDir.exists()) {
		    IJ.log("creating directory: " + saveDir.getName());
		    try{
		    	saveDir.mkdir();
		    } catch(SecurityException se){
		        IJ.log("Cannot create directory! Check accessibility.");
		        return false;
		    }
		}
		
		return true;
		
	}
	
	
	public void convertCZIFileToTiff(
			String dirPath,
			boolean doSubDir,
			boolean doBackgroundSubtraction,
			double bgRadius,
			String saveDir,
			boolean reproduceDirStructure
			) throws FormatException, IOException, ServiceException {
		
		//
		List<File> fileList = new ArrayList<File>();
		if (dirPath.endsWith(".czi")) {// user selected a file instead of a folder
			fileList = Arrays.asList(new File(dirPath));
		} else {
			fileList = getFileList(dirPath, doSubDir, ".czi");
		}
		
		IJ.log("Located the following czi Files:");
		for (int i = 0; i<fileList.size(); i++) {
			IJ.log("   " + fileList.get(i).getAbsolutePath());
		}
		
		for (int f = 0; f < fileList.size(); f++) {
			try {
				// get current CZI file
				File currentCZIFile = fileList.get(f);
				String fileAbsolutePath = currentCZIFile.getAbsolutePath();
				String fileName = FilenameUtils.getBaseName(fileAbsolutePath);
				String filePath = FilenameUtils.getFullPathNoEndSeparator(fileAbsolutePath);
				String savePath = saveDir + File.separator + fileName;
				if (reproduceDirStructure) {
					savePath = filePath.replace(dirPath, saveDir) + File.separator + fileName;
				}
				//IJ.log("save path: " + savePath);
				File saveDirPath = new File(savePath);
				if (!saveDirPath.exists()) {
					saveDirPath.mkdirs();	
				}
				
				ImageProcessorReader  r = new ImageProcessorReader(new ChannelSeparator(LociPrefs.makeImageReader()));
				// set up metadata reading
				OMEXMLServiceImpl OMEXMLService = new OMEXMLServiceImpl();
				r.setMetadataStore(OMEXMLService.createOMEXMLMetadata());
				// set reader to specified file
				r.setId(fileAbsolutePath);
				// get metadata
				MetadataRetrieve meta = (MetadataRetrieve) r.getMetadataStore();
				// get number of series in specified file
				int numSeries = meta.getImageCount();
				int nZeros = String.valueOf(numSeries).length();
				
				for (int i = 0; i < numSeries; i++) {
					IJ.log ("Processing file " 
							+ String.valueOf(f+1) + " of " + String.valueOf(fileList.size()) 
							+ ", series " 
							+ String.valueOf(i+1) + " of " + String.valueOf(numSeries));
						
					// set reader to current series
					r.setSeries(i);
					// load data for current series as a stack
					ImageStack stack = new ImageStack(r.getSizeX(), r.getSizeY());
					for (int n = 0; n < r.getImageCount(); n++) {
						ImageProcessor ip = r.openProcessors(n)[0];
						stack.addSlice("" + (n + 1), ip);
					}
					// create ImagePlus using stack
					ImagePlus imp = new ImagePlus("", stack);
					// convert to HyperStack with correct dimensions
					imp = HyperStackConverter.toHyperStack(imp, r.getSizeC(), r.getSizeZ(), r.getSizeT());
					
					
					// do background subtraction
					ImagePlus impBgSubtracted = null;
					if (r.getSizeC()==4 && doBackgroundSubtraction) {
						IJ.log("   performing background subtraction");
						ImagePlus[] channels = ChannelSplitter.split(imp);
						imp.close();
						String param = "rolling=" + String.valueOf(bgRadius) +" stack";
						IJ.run(channels[0], "Subtract Background...", param);
						IJ.run(channels[1], "Subtract Background...", param);
						IJ.run(channels[2], "Subtract Background...", param);
						impBgSubtracted = RGBStackMerge.mergeChannels(channels, false);
					} else {
						impBgSubtracted = imp;
						imp.close();
					}
					
					for (int c=1; c<impBgSubtracted.getNChannels()+1; c++) {
						impBgSubtracted.setC(c);
						IJ.resetMinAndMax(impBgSubtracted);
					}


					// set calibration for data using metadata
					Calibration cali = new Calibration();
					cali.setUnit("pixel");
					if (meta.getPixelsPhysicalSizeX(i) != null) {
						cali.pixelWidth = meta.getPixelsPhysicalSizeX(i).value(UNITS.MICROMETER).doubleValue();
						//cali.pixelWidth = meta.getPixelsPhysicalSizeX(i).getValue();
						cali.setUnit("micron");
					}
					if (meta.getPixelsPhysicalSizeX(i) != null) {
						cali.pixelHeight = meta.getPixelsPhysicalSizeY(i).value(UNITS.MICROMETER).doubleValue();
						//cali.pixelHeight = meta.getPixelsPhysicalSizeY(i).getValue();
						cali.setUnit("micron");
					}
					if (meta.getPixelsPhysicalSizeZ(i)!=null && r.getSizeZ()>1) {
						cali.pixelDepth = meta.getPixelsPhysicalSizeZ(i).value(UNITS.MICROMETER).doubleValue();
						//cali.pixelDepth = meta.getPixelsPhysicalSizeZ(i).getValue();
						cali.setUnit("micron");
					}
					if (meta.getPixelsTimeIncrement(i)!=null && r.getSizeT()>1) {
						cali.frameInterval = meta.getPixelsTimeIncrement(i).value(UNITS.SECOND).doubleValue();
						//meta.getPixelsTimeIncrement(i).doubleValue();
					}

					impBgSubtracted.setCalibration(cali);

					// set imp title to series name
					impBgSubtracted.setTitle(meta.getImageName(i));
	
					// save series as tif in output directory
					String seriesName = "_series" + String.format("%0"+String.valueOf(nZeros)+"d", i+1);
					IJ.log("   saved as: ");
					IJ.log("   " + savePath + File.separator + fileName + seriesName + ".tif");
					IJ.saveAs(impBgSubtracted, "Tiff", savePath + File.separator + fileName + seriesName + ".tif");
					
					// close the reader and image
					
					impBgSubtracted.close();
				}
				r.close();
			} catch (NullPointerException e) {	// if something is missing, skip this file
				IJ.log("File \"" + fileList.get(f).getAbsolutePath() + "\" not converted!");
				continue;
			}
		}
	}
	
	public List<File> getFileList(
			String directoryName,
			boolean doSubDir,
			String ext
			) {
	        
		File directory = new File(directoryName);
        List<File> resultList = new ArrayList<File>();
        // get all the files from a directory
        File[] fList = directory.listFiles();
        for (File file : fList) {
            if (file.isFile()) {
                if (file.toString().toLowerCase().endsWith(ext)) {
                	resultList.add(file);
                }
            } else if (file.isDirectory()) {
                if (doSubDir) {
                	resultList.addAll(getFileList(file.getAbsolutePath(), doSubDir, ext));
                }
            }
        }
        return resultList;
    } 
	
	@Override
	public void run(String arg) {
		
		if (!cziConverterDialog()) {
			return;
		}
		
		IJ.log(GetDateAndTime.getCurrentDate());
		IJ.log(GetDateAndTime.getCurrentTime());
		long start = GetDateAndTime.getCurrentTimeInMs();

		try {
			convertCZIFileToTiff(
					rootDirPath,
					doSubDir,
					doBackgroundSubtraction,
					bgRadius,
					saveDirPath,
					reproduceDirStructure
					);
		} catch (FormatException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
		
		long end = GetDateAndTime.getCurrentTimeInMs();
		String duration = GetDateAndTime.getDuration(end-start);
		IJ.log("CZI converter completed successfully after : " + duration);
	}

	
	public static void main(String[] args) {
		
		// make use of scijava parameter persistence storage	
		DefaultPrefService prefs = new DefaultPrefService();
		rootDirPath = prefs.get(String.class, "persistedString", rootDirPath);
		doSubDir = prefs.getBoolean(Boolean.class, "persistedBoolean", doSubDir);
		doBackgroundSubtraction = prefs.getBoolean(Boolean.class, "persistedBoolean", doBackgroundSubtraction);
		bgRadius = prefs.getDouble(Double.class, "persistedDouble", bgRadius);
		saveDirPath = prefs.get(String.class, "persistedString", saveDirPath);
		reproduceDirStructure = prefs.getBoolean(Boolean.class, "persistedBoolean", reproduceDirStructure);
		
		CZIFileConverter cfc = new CZIFileConverter();
		cfc.run(null);
		
		
	}
	
}
