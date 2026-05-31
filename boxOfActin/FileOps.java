package boxOfActin;
import infoCCD.Info;
import infoCCD.ResourceGetter;

import java.lang.Math;
import java.net.URL;
import java.awt.*;
import java.io.*;
import java.text.DecimalFormat;
import java.util.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.JFileChooser;
import javax.swing.JFrame;

public class FileOps {
	// type definitions
	static final int NOTHING = 999;
	static final int TIME = -1;
	static final int ACTIN_CONCENTRATION = -2;
	static final int NUM_FILS = -3;
	static final int NUM_MONS = -4;
	static final int NUM_FILSEGS = -5;
	static final int NUM_FILLINKS = -6;
	static final int NUM_PLASMIDS = -7;
	static final int NUM_MYOSINS = -8;
	static final int NUM_MYOSINDIMERS = -9;
	static final int NUM_MYOMINIFILS = -10;
	static final int NUM_THINGS = 0;
	static final int FILSEGMENT = 1;
	static final int PLASMID = 2;
	static final int BOX = 3;
	static final int FILLINK = 4;
	static final int MYOSIN = 5;
	static final int MYOMOTOR = 6;
	static final int MYOLEVER = 7;
	static final int MYOROD = 8;
	static final int MYOMINIFIL = 9;
	static final int BUG = 10;

	
	static String sepString = ",";
	static String divStr = ":";
	static String endStr = ";";
	static String idDivStr = ":";
	static String commentStr = "//";
	// file, file writer, and print writer for program errors to file
	static File errorsOutFile;
	static FileOutputStream errorsOutFOS;
	static PrintStream errorsOutPS;	
	// file, file writer, and print writer for program messages to file
	static File messagesOutFile;
	static FileWriter messagesOutFW;
	static PrintWriter messagesOutPW;	
	// file, file writer, and print writer for information file
	static File outFile;
	static FileWriter outFW;
	static PrintWriter outPW;
	// file, file writer, and print writer for persistence length file
	static File lpFile;
	static FileWriter lpFW;
	static PrintWriter lpPW;
//	 file, file writer, and print writer for apparent diffusivity file
	static File aDFile;
	static FileWriter aDFW;
	static PrintWriter aDPW;
	// files and writers for filament lengths file
	static File filLengthFile;
	static FileWriter filLengthFW;
	static PrintWriter filLengthPW;
	// files and writers for gliding assay file
	static File gAssayFile;
	static FileWriter gAssayFW;
	static PrintWriter gAssayPW;
	// files and writers for filament length histogram file
	static File filLengthHistoFile;
	static FileWriter filLengthHistoFW;
	static PrintWriter filLengthHistoPW;
	// files and writers for filament length histogram file
	static File relaxFile;
	static FileWriter relaxFileFW;
	static PrintWriter relaxFilePW;

	// flags for file setup
	static boolean outFileMade = false;
	static boolean lpFileMade = false;
	static boolean aDFileMade = false;
	static boolean filLengthFileMade = false;
	static boolean gAssayFileMade = false;
	static boolean filLengthHistoFileMade = false;
	static boolean relaxFileMade = false;
	
	// file id formating
	static DecimalFormat fileIdFormat = new DecimalFormat ("#000000000.#;#000000000.#");
	static DecimalFormat fileFromSimpleCounterFormat = new DecimalFormat ("#00000.#;#00000.#");

	// file, file writer, and print writer for Simularium JSON file
	static File jSonFile;
	static FileWriter jSonFW;
	static PrintWriter jSonPW;
	static File jSonFile2;
	static FileWriter jSonFW2;
	static PrintWriter jSonPW2;

	// counters
	static int [] idsThisFrame = new int[100000];
	static int idCt = 0;
	static int simJSonCounter = 0;		// keep track of how many frames we've written
	static int simJSonSteps = (int)(Env.simJSonSavesPerSec*Env.runTime.getValue()+1);	// this is how many frames we'll write in JSon serialization
	static int simJSonPlotCounter = 0;
	static int simJSonPlotSteps = (int)(Env.simJSonPlotSavesPerSec*Env.runTime.getValue()+1);	// this is how many frames we'll write in JSon serialization
	static int simJSon2Counter = 0;		// keep track of how many frames we've written
	static int simJSon2Steps = (int)(Env.simJSon2SavesPerSec*(Env.simJSon2Stop-Env.simJSon2Start)+1);	// this is how many frames we'll write in JSon 2 serialization
	
	// data structures for storing values for Simularium JSON
	static double [] timeSteps = new double[simJSonPlotSteps];
	static double [][] lmForces = new double[5][simJSonPlotSteps]; // #1: forces on bug
	static String [] lmForcesNames = new String[]{"filament collision force","restraining Act link force","kinetic friction force","tip force normal","total force"};
	static double [] aveActiveLinks = new double[simJSonPlotSteps]; // #2: averaged active ActA links
	// #3 and #4 are histograms
	static double [] linksBroken = new double[simJSonPlotSteps];
	static double [] linksFormed = new double[simJSonPlotSteps];
	static double [] lmPosx = new double[simJSonPlotSteps]; // #5: bacterial position along path
	static double [][] lmPos = new double[3][simJSonPlotSteps]; // #5+: y and z too, if needed
	static String [] lmPosNames = new String[]{"x","y","z"};
	
	static double [] polyPrettyClose = new double[simJSonPlotSteps];	// #6: polymerization close to the bacterial surface
	static double [] tipsPrettyClose = new double[simJSonPlotSteps];	// #7: barbed-ends close to the bacterial surface
	static double [] arpsPrettyClose = new double[simJSonPlotSteps];	// #8: arps close
	
	// data structures for storing values for Simularium JSON, second JSon file
	static double [] timeSteps2 = new double[simJSon2Steps];
	static double [][] lmForces2 = new double[5][simJSon2Steps]; // #1: forces on bug
	static String [] lmForcesNames2 = new String[]{"filament collision force","restraining Act link force","kinetic friction force","tip force normal","total force"};
	static double [] aveActiveLinks2 = new double[simJSon2Steps]; // #2: averaged active ActA links
	// #3 and #4 are histograms
	static double [] linksBroken2 = new double[simJSon2Steps];
	static double [] linksFormed2 = new double[simJSon2Steps];
	static double [] lmPosx2 = new double[simJSon2Steps]; // #5: bacterial position along path
	static double [][] lmPos2 = new double[3][simJSon2Steps]; // #5+: y and z too, if needed
	static String [] lmPosNames2 = new String[]{"x","y","z"};
	
	static double [] polyPrettyClose2 = new double[simJSon2Steps];	// #6: polymerization close to the bacterial surface
	static double [] tipsPrettyClose2 = new double[simJSon2Steps];	// #7: barbed-ends close to the bacterial surface
	static double [] arpsPrettyClose2 = new double[simJSon2Steps];	// #8: arps close
	
	
	public FileOps () { }
	

	// A class to filter out .java files
	class SourceFileFilter implements FilenameFilter {

    /**
    * Select only .java files.
    *
    * @param dir the directory in which the file was found.
    *
    * @param name the name of the file
    *
    * @return true if and only if the name should be
    * included in the file list; false otherwise.
    */
    public boolean accept ( File dir, String name )
      {
      if ( new File( dir, name ).isDirectory() )
         {
         return false;
         }
      name = name.toLowerCase();
      return name.endsWith( ".java" );
      }
    }
	
	//	 A class to filter out .param files
	class ParamFileFilter implements FilenameFilter {

    /**
    * Select only .param files.
    *
    * @param dir the directory in which the file was found.
    *
    * @param name the name of the file
    *
    * @return true if and only if the name should be
    * included in the file list; false otherwise.
    */
    public boolean accept ( File dir, String name )
      {
      if ( new File( dir, name ).isDirectory() )
         {
         return false;
         }
      name = name.toLowerCase();
      return name.endsWith( ".param" );
      }
    }
	
	// A class to filter out .qk files
	static class QKFileFilter implements FilenameFilter {

    /**
    * Select only .qk files.
    *
    * @param dir the directory in which the file was found.
    *
    * @param name the name of the file
    *
    * @return true if and only if the name should be
    * included in the file list; false otherwise.
    */
    public boolean accept ( File dir, String name )
      {
      if ( new File( dir, name ).isDirectory() )
         {
         return false;
         }
      name = name.toLowerCase();
      return name.endsWith( ".qk" );
      }
    }
    
    public void copyFile(File in, File out) throws Exception {
	    FileInputStream fis  = new FileInputStream(in);
	    FileOutputStream fos = new FileOutputStream(out);
	    byte[] buf = new byte[1024];
	    int i = 0;
	    while((i=fis.read(buf))!=-1) {
	      fos.write(buf, 0, i);
	    }
	    fis.close();
	    fos.close();
	}
    
    public static String [] getQKFileList (File dir) {
    		return dir.list(new QKFileFilter());
    }
    
	public static String getDirectoryToSave (String dialogTitle, Component parent) {
		File file = null;
		String fileName = null;
		JFileChooser saveTo = new JFileChooser();
		saveTo.setDialogTitle(dialogTitle);
		saveTo.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int fileQuery = saveTo.showSaveDialog(parent);
		if (fileQuery == JFileChooser.APPROVE_OPTION) {
            file = saveTo.getSelectedFile();
            if (!file.exists()) { file.mkdir(); }
            fileName = file.getAbsolutePath();
            //talkln (dialogTitle + fileName);
        } else {
            talkln ("Save cancelled by user");
            return null;
        }
        return fileName;
	}
	
	public static String getFileToSave (String dialogTitle, JFrame parent) {
		File file = null;
		String fileName = null;
		JFileChooser saveTo = new JFileChooser();
		saveTo.setDialogTitle(dialogTitle);
		int fileQuery = saveTo.showSaveDialog(parent);
		if (fileQuery == JFileChooser.APPROVE_OPTION) {
            file = saveTo.getSelectedFile();
            fileName = file.getAbsolutePath();
            //talkln (dialogTitle + fileName);
        } else {
            talkln ("Save cancelled by user");
            return null;
        }
        return fileName;
	}
	
	public static String getDirectoryToLoad (String dialogTitle, JFrame parent) {
		File file = null;
		String fileName = null;
		JFileChooser loadFrom = new JFileChooser();
		loadFrom.setDialogTitle(dialogTitle);
		loadFrom.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int fileQuery = loadFrom.showOpenDialog(parent);
		if (fileQuery == JFileChooser.APPROVE_OPTION) {
            file = loadFrom.getSelectedFile();
            fileName = file.getAbsolutePath();
            //talkln (dialogTitle + fileName);
        } else {
            talkln ("Load cancelled by user");
            return null;
        }
        return fileName;
	}
	
	public static String getFileToLoad (String dialogTitle, JFrame parent) {
		File file = null;
		String fileName = null;
		JFileChooser loadFrom = new JFileChooser();
		loadFrom.setDialogTitle(dialogTitle);
		loadFrom.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		int fileQuery = loadFrom.showOpenDialog(parent);
		if (fileQuery == JFileChooser.APPROVE_OPTION) {
            file = loadFrom.getSelectedFile();
            fileName = file.getAbsolutePath();
            //talkln (dialogTitle + fileName);
        } else {
            talkln ("Load cancelled by user");
            return null;
        }
        return fileName;
	}
	
	public static String makeNameFromIntStep (String base, int integrationStep) {
		return (base + String.valueOf(fileIdFormat.format(integrationStep)));
	}
	
	public static String makeNameFromSimpleCounter (String base, int counter) {
		return (base + String.valueOf(fileFromSimpleCounterFormat.format(counter)));
	}
	
	public void mkErrorFile () {
		try {
			errorsOutFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "Errors");
			errorsOutFOS = new FileOutputStream(errorsOutFile);
			errorsOutPS = new PrintStream(errorsOutFOS);
			System.setErr(errorsOutPS);
			//Date timeNow = new Date();
			//errorsOutPW.println("This is the errors file for " + Env.outFileName + " run on " + timeNow.toString());
		} catch (IOException ioe) { talkln ("An error creating errorsOut stuff"); }
	}
		
	public void mkMessageFile () {
		try {
			messagesOutFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "Messages");
			messagesOutFW = new FileWriter(messagesOutFile);
			messagesOutPW = new PrintWriter(messagesOutFW, true);
			Date timeNow = new Date();
			messagesOutPW.println("This is the message file for " + Env.outFileName + " run on " + timeNow.toString());
		} catch (IOException ioe) { talkln ("An error creating messagesOut stuff"); }
	}
	
	public void mkEnvironmentFile () {
		File envFile = new File ("Env.java");
		if ( ! envFile.exists() || ! envFile.canRead() ) {
			talkln ("Can't find 'Env.java'... you'd better store those values manually");
		} else {
			try {
				FileReader fileR = new FileReader(envFile);
				BufferedReader in = new BufferedReader(fileR);
				File envOut = new File(Env.logFolderPath + File.separator + Env.outFileName + "Environment");
				FileWriter envOutFW = new FileWriter(envOut);
				PrintWriter envOutPW = new PrintWriter(envOutFW, true);
				Date timeNow = new Date();
				envOutPW.println("This is the environment file for " + Env.outFileName + " run on " + timeNow.toString());
				String nextLine = in.readLine();
				while (nextLine != null) {
					envOutPW.println(nextLine);
					nextLine = in.readLine();
				}
			} catch (IOException ioe) { talkln ("An error trying to save environment file"); }
		}
	}
	
	public void mkSourceFolder () {
		try {
			File curDir = new File (".");
			System.out.println ("Directory for source files is: " + curDir.getCanonicalPath());
			String [] srcFiles = curDir.list(new SourceFileFilter());
			for (int i=0;i<srcFiles.length;i++) {
				File curSrcFile = new File (srcFiles[i]);
				File curOutSrcFile = new File (Env.srcFilePath + File.separator + curSrcFile.getName());
				copyFile(curSrcFile,curOutSrcFile);
			}
		} catch (Exception e) { talkln ("An error trying to copy source files"); }
	}
	
	public static void mkOutFile (String outFileString) {
		try {
			if (Env.remote) {
				outFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "Out");
			} else {
				outFile = new File(outFileString);
			}
			outFW = new FileWriter(outFile);
			outPW = new PrintWriter(outFW,true);
			Date timeNow = new Date();
			outPW.println("This is the out file for " + outFile.getPath() + " run on " + timeNow.toString());
			outPW.print("time"+sepString);
			outPW.print("path.x"+sepString);
			outPW.print("path.y"+sepString);
			outPW.print("path.z"+sepString);
			outPW.print("Actin concentration"+sepString);
			outPW.print("fil length ave"+sepString);
			outPW.print("fil length stdev"+sepString);
			outPW.println("");
			outFileMade = true;
		} catch (IOException ioe) { talkln ("An error creating out file stuff"); }
	}
	
	
	public static void mkFilLengthFile () {
		try {
			filLengthFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "Filaments");
			filLengthFW = new FileWriter(filLengthFile);
			filLengthPW = new PrintWriter(filLengthFW,true);
			Date timeNow = new Date();
			filLengthPW.println("This is the filament lengths file for " + Env.outFileName + " run on " + timeNow.toString());
			filLengthPW.print("time;");
			filLengthPW.println("");
			filLengthFileMade = true;
		} catch (IOException ioe) { talkln ("An error creating filament length file stuff"); }
	}
	
	public static void mkGlidingAssayOutFile () {
		try {
			if (Env.remote) {
				gAssayFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "GlidingAssay");
			} else {
				gAssayFile = new File (getFileToSave("Gliding Assay Position File is ...", null));
			}
			gAssayFW = new FileWriter(gAssayFile);
			gAssayPW = new PrintWriter(gAssayFW,true);
			Date timeNow = new Date();
			gAssayPW.println("sep="+sepString);
			gAssayPW.println("This is a gliding assay position file with a myosin density of " + Env.fixedMyosinDensity + " /µm^2, run on " + timeNow.toString());
			gAssayPW.println("time;position");
			gAssayFileMade = true;
		} catch (IOException ioe) { talkln ("An error creating gliding assay file stuff"); }
	}
	

	public static void writeToOutFile () {
		//if (!outFileMade) { mkOutFile(); }
		printValue(Env.simulationTime,outPW);
		printValue(Thing.lmBug.pathCoord.x,outPW);
		printValue(Thing.lmBug.pathCoord.y,outPW);
		printValue(Thing.lmBug.pathCoord.z,outPW);
		printValue(Env.actinConc.getValue(),outPW);
		Stat filStats = FilSegment.filLengthStatistics();
		printValue(filStats.getMean(),outPW);
		printValue(filStats.getStdDev(),outPW);
		outPW.println("");
	}
	
	public static void writeToFilLengthFile () {
		if (!filLengthFileMade) { mkFilLengthFile(); }
		printValue(Env.simulationTime,filLengthPW);
		for (int i=0;i<FilSegment.filSegmentCt;i++) {
			filLengthPW.print(String.valueOf(FilSegment.theFilSegments[i].filID));
			filLengthPW.print(idDivStr);
			printValue(FilSegment.theFilSegments[i].length, filLengthPW);
		}
		filLengthPW.println("");
	}
	
	public static void writeToGAssayFile () {
		if (!gAssayFileMade) { mkGlidingAssayOutFile(); }
		printValue(Env.simulationTime,gAssayPW);
		gAssayPW.println(String.valueOf(FilSegment.theFilSegments[0].getCoordX()));
	}
	
	public static void writeGlidingAssayDataSet () {
		try {
			System.out.println("In writeGlidingAssayDataSet");
			if (Env.remote) {
				gAssayFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "GlidingAssay");
			} else {
				gAssayFile = new File (getFileToSave("Gliding Assay Data Set File is ...", null));
			}
			gAssayFW = new FileWriter(gAssayFile);
			gAssayPW = new PrintWriter(gAssayFW,true);
			Date timeNow = new Date();
			gAssayPW.println("sep="+sepString);
			gAssayPW.println("This is a gliding assay dataset file with a myosin increment of " + MyosinFixed.myoDensityIncrement + " /µm^2, run on " + timeNow.toString());
			// write all headers first
			for (int i = 0; i<MyosinFixed.numRunCols;i++) { gAssayPW.print(MyosinFixed.posHeaders[i]); }
			gAssayPW.println(" ");
			// nested loop to print all position value... assume all time points are same, which they should be
			for (int i=0; i<MyosinFixed.numPosPts;i++) {
				for (int j=0;j<MyosinFixed.numRunCols;j++) {
					gAssayPW.print(String.valueOf(MyosinFixed.posData[i][j])+sepString);
				}
				gAssayPW.println(" ");
			}
		} catch (IOException ioe) { talkln ("An error creating gliding assay file stuff"); }
	}
	
	private static void printDivider (PrintWriter pw) {
		pw.print(sepString);
	}
	
	private static void printValue (int val, PrintWriter pw) {
		pw.print(String.valueOf(val));
		printDivider(pw);
	}
	
	private static void printIntValue (double val, PrintWriter pw) {
		int intVal = (int) val;
		pw.print(String.valueOf(intVal));
		printDivider(pw);
	}
	
	private static void printValue (double val, PrintWriter pw) {
		pw.print(String.format("%.4f",val));
		printDivider(pw);
	}
	
	private static void printPt3D (Pt3D pt, PrintWriter pw) {
		pw.print(String.valueOf(pt.x));
		printDivider(pw);
		pw.print(String.valueOf(pt.y));
		printDivider(pw);
		pw.print(String.valueOf(pt.z));
		printDivider(pw);
	}
	
	public static void remoteParamConfigSave () {
		File configFile = new File (Env.logFolderPath + File.separator + Env.outFileName + "Params");
		writeParamConfig (configFile);
	}
	
	public static void writeParamConfig (File paramOutFile) {
		talk ("Saving parameter values to " + paramOutFile.getAbsolutePath() + "...");
		try {
			FileWriter paramOutFW = new FileWriter(paramOutFile);
			PrintWriter paramOutPW = new PrintWriter(paramOutFW, true);
			Date timeNow = new Date();
			paramOutPW.println(Info.appParamID + " This is a parameter configuration file created on " + timeNow.toString());
			Parameter curParam;
			String firstStr;
			for (int i=0;i<Parameter.paramCt;i++) {
				curParam = Parameter.theParams[i];
				firstStr = curParam.label + divStr + String.valueOf(curParam.isActive()) + divStr + String.valueOf(curParam.getValue()) + endStr;
				paramOutPW.print(firstStr);
				int spacesToAdd = 40-firstStr.length();
				for (int j=0;j<spacesToAdd;j++) { paramOutPW.print(" "); }
				paramOutPW.println(commentStr + curParam.parameterName);
			}
			talkln ("done.");
		} catch (IOException ioe) { talkln ("An error trying to write the parameter config file"); }
	}

/*	public static void loadParamConfig (File paramFile, boolean restartNeeded) {
		talkln ("Loading parameter values from " + paramFile.getAbsolutePath() + "...");
		int misunderstandings = 0;
		if ( ! paramFile.exists() || ! paramFile.canRead() ) {
			talkln ("Can't find or access " + paramFile.getAbsolutePath() + " ... check the file path and name");
		} else {
			try {
				FileReader fileR = new FileReader(paramFile);
				BufferedReader in = new BufferedReader(fileR);
				String curLine = in.readLine();
				String curLabel,curValueStr,curActiveStr;
				boolean curActive = true;
				double curValue = 0;
				int div1Ind,div2Ind,endInd;
				while (curLine != null) {
					if (!curLine.startsWith(commentStr) & !curLine.startsWith(Info.appParamID)) {
						// parse label and value strings
						div1Ind = curLine.indexOf(divStr);
						div2Ind = curLine.indexOf(divStr,div1Ind+1);
						endInd = curLine.indexOf(endStr);
						curLabel = curLine.substring(0,div1Ind).trim();
						curActiveStr = curLine.substring(div1Ind+1,div2Ind).trim();
						curValueStr = curLine.substring(div2Ind+1,endInd);
						// make value string a double
						try {
							Double valD = Double.valueOf(curValueStr);
							curValue = valD.doubleValue();
						} catch (java.lang.NumberFormatException nfe) {
							talkln("Invalid parameter value, " + curValueStr + " loaded from " + paramFile.getAbsolutePath() + "... exiting");
							System.exit(0);
						}
						// set boolean
						if (curActiveStr.equals("false")) { curActive = false; } else { curActive = true; }
						// find the matching parameter label from static list of parameters
						boolean matchFound = false;
						for (int i=0;i<Parameter.paramCt;i++) {
							if (Parameter.theParams[i].label.equals(curLabel)) {
								Parameter.theParams[i].setValue(curValue);
								Parameter.theParams[i].setActive(curActive);
								matchFound = true;
								if (Parameter.theParams[i].type == Parameter.BOOLEAN) {
									talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].isActive()));
								} else if (Parameter.theParams[i].type == Parameter.INT) {
									talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].getIntValue()));
								} else {
									talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].getValue()));
								}
							}
							if (matchFound) { break; }
						}
						if (!matchFound) { 
							talkln ("     No match found for parameter label " + curLabel + " ... you best check that out!"); 
							misunderstandings++;
						}
					}
					// next line
					curLine = in.readLine();
				}
			} catch (IOException ioe) { talkln ("An error trying to load param file"); }
		}
		talkln ("Done... there were " + String.valueOf(misunderstandings) + " misunderstandings.");
		if (restartNeeded) {
			System.out.print ("Restarting simulation... ");
			BoxOfActin.restartRun();
			talkln("done.");
		}
		if (Env.remote) { 
			BoxOfActin.setRunning(); 
		} else {
			ParamGui.syncAllToParameters();
		}
		
	}
	*/
	
	public static void loadParamConfig (File paramFile, boolean restartNeeded) {
		talkln ("Loading parameter values from " + paramFile.getAbsolutePath() + "...");
		int misunderstandings = 0;
		if ( ! paramFile.exists() || ! paramFile.canRead() ) {
			talkln ("Can't find or access " + paramFile.getAbsolutePath() + " ... check the file path and name");
		} else {
			try {
				FileReader fileR = new FileReader(paramFile);
				BufferedReader in = new BufferedReader(fileR);
				String curLine = in.readLine();
				String fromArg = paramFile.getAbsolutePath();
				while (curLine != null) {
					if (!curLine.startsWith(commentStr) & !curLine.startsWith(Info.appParamID) & (curLine.length() >= 1)) {
						boolean matchFound = loadParamLine(curLine,fromArg);
						if (!matchFound) { misunderstandings++; }
					}
					// next line
					curLine = in.readLine();
					
				}
			} catch (IOException ioe) { talkln ("An error trying to load param file"); }
		}
		talkln ("Done... there were " + String.valueOf(misunderstandings) + " misunderstandings.");
		if (restartNeeded) {
			System.out.print ("Restarting simulation... ");
			BoxOfActin.restartRun(true);
			talkln("done.");
		}
		if (Env.remote) BoxOfActin.setRunning();

	}

	public static boolean loadParamLine (String curLine, String fromArg) {
		String curLabel,curValueStr,curActiveStr;
		boolean curActive = true;
		double curValue = 0;
		int div1Ind,div2Ind,endInd;
		// parse label and value strings
		div1Ind = curLine.indexOf(divStr);
		div2Ind = curLine.indexOf(divStr,div1Ind+1);
		endInd = curLine.indexOf(endStr);
		if ((div1Ind == -1) | (div2Ind == -1) | (endInd == -1)) { return false; }
		curLabel = curLine.substring(0,div1Ind).trim();
		curActiveStr = curLine.substring(div1Ind+1,div2Ind).trim();
		curValueStr = curLine.substring(div2Ind+1,endInd);
		// make value string a double
		try {
			Double valD = Double.valueOf(curValueStr);
			curValue = valD.doubleValue();
		} catch (java.lang.NumberFormatException nfe) {
			talkln("Invalid parameter value, " + curValueStr + " loaded from " + fromArg + "... exiting");
			System.exit(0);
		}
		// set boolean
		if (curActiveStr.equals("false")) { curActive = false; } else { curActive = true; }
		// find the matching parameter label from static list of parameters
		boolean matchFound = false;
		for (int i=0;i<Parameter.paramCt;i++) {
			if (Parameter.theParams[i].label.equals(curLabel)) {
				Parameter.theParams[i].setValue(curValue);
				Parameter.theParams[i].setActive(curActive);
				matchFound = true;
				if (Parameter.theParams[i].type == Parameter.BOOLEAN) {
					talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].isActive()));
				} else if (Parameter.theParams[i].type == Parameter.INT) {
					talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].getIntValue()));
				} else {
					talkln ("     Parameter assigned: " + curLabel + " set to " + String.valueOf(Parameter.theParams[i].getValue()));
				}
			}
			if (matchFound) { break; }
		}
		if (!matchFound) { 
			talkln ("     No match found for parameter label " + curLabel + " ... you best check that out!"); 
		}
		return matchFound;
		
	}
	
	public static void loadParamsFromViewer (String viewStr) {
		talkln ("Loading parameter values from viewer...");
		String fromArg = "the viewer";
		int misunderstandings = 0;
		int bolIndex = 0;
		int eolIndex = 0;
		String curLine;
		int finalIndex = viewStr.lastIndexOf("\n");
		while (bolIndex < finalIndex) {
			eolIndex = viewStr.indexOf("\n",bolIndex);
	    	curLine = viewStr.substring(bolIndex,eolIndex);
	    	//System.out.println(curLine);
	    	if (!curLine.startsWith(commentStr) & !curLine.startsWith(Info.appParamID) & (curLine.length() >= 1)) {
				boolean matchFound = loadParamLine(curLine,fromArg);
				if (!matchFound) { misunderstandings++; }
			}
			// next line
			bolIndex = eolIndex+1;
		}
		talkln ("Done... there were " + String.valueOf(misunderstandings) + " misunderstandings.");
		System.out.print ("Restarting simulation... ");
		BoxOfActin.restartRun(true);
		talkln("done.");
		if (Env.remote) BoxOfActin.setRunning();

	}

	synchronized public static void addJSonID (int id) {
		idsThisFrame[idCt]=id;
		idCt++;
	}
	
	public static void checkUniqueJSonIDs () {
		boolean failedCk = false;
		for (int i=0;i<idCt-1;i++) {
			int compID = idsThisFrame[i];
			for (int j=i+1;j<idCt;j++) {
				if (compID == idsThisFrame[j]) {
					System.out.println ("You've got duplicate JSON ID numbers:" + compID + " = " + idsThisFrame[j]); 
					failedCk = true;
				}
			}
		}
		//if (!failedCk) { System.out.println("** All unique IDs in frame #" + (simJSonCounter+1)); }
	}
	
	public static void setupJSons() {
		Env.writeSimJSons = true;
		Env.writeSimJSons2 = true;
		mkSimulariumJSonFile();
		mkSimulariumJSonFile2();
	}
	
	public static void mkSimulariumJSonFile () {
		try {
			jSonFile = new File (Env.parentFolderPath + File.separator + Env.jSonFileName + ".json");
			jSonFW = new FileWriter(jSonFile);
			jSonPW = new PrintWriter(jSonFW,true);
			
			// some formatting of values
			String dimXStr = String.format("%.2f",Env.simJSonsScale*Env.boxXDim.getValue());
			String dimYStr = String.format("%.2f",Env.simJSonsScale*Env.boxYDim.getValue());
			String dimZStr = String.format("%.2f",Env.simJSonsScale*Env.boxZDim.getValue());
			// make header in Simularium style
			String headString = "{";
			headString += "\"trajectoryInfo\": {";
			headString += "\"version\": 1,";
			headString += "\"timeStepSize\": "+(1.0/Env.simJSonSavesPerSec)+",";
			headString += "\"totalSteps\": "+simJSonSteps+",";
			headString += "\"size\": {\"x\": "+dimXStr+",\"y\": "+dimYStr+",\"z\": "+dimZStr+"},";
			headString += "\"typeMapping\": {\"0\": {\"name\" : \"LM\"},\"1\": {\"name\": \"ADP\"},\"2\": {\"name\": \"ADPPi\"},\"3\": {\"name\": \"ATP\"},\"4\": {\"name\": \"CAP\"},\"5\": {\"name\": \"ARP23\"},\"6\": {\"name\": \"ACTA\"},\"7\": {\"name\": \"XLINK\"}}";
			headString += "},";
			headString += "\"spatialData\": {\"version\": 1,\"msgType\": 1,\"bundleStart\": 0,\"bundleSize\": "+simJSonSteps+",\"bundleData\": [";
			jSonPW.print(headString);
		} catch (IOException ioe) { talkln ("An error creating Simularium JSON file"); }
	}
	
	public static void mkSimulariumJSonFile2 () {
		try {
			jSonFile2 = new File (Env.parentFolderPath + File.separator + Env.jSon2FileName + ".json");
			jSonFW2 = new FileWriter(jSonFile2);
			jSonPW2 = new PrintWriter(jSonFW2,true);
			
			// some formatting of values
			String dimXStr = String.format("%.2f",Env.simJSonsScale*Env.boxXDim.getValue());
			String dimYStr = String.format("%.2f",Env.simJSonsScale*Env.boxYDim.getValue());
			String dimZStr = String.format("%.2f",Env.simJSonsScale*Env.boxZDim.getValue());
			// make header in Simularium style
			String headString = "{";
			headString += "\"trajectoryInfo\": {";
			headString += "\"version\": 1,";
			headString += "\"timeStepSize\": "+(1.0/Env.simJSon2SavesPerSec)+",";
			headString += "\"totalSteps\": "+simJSon2Steps+",";
			headString += "\"size\": {\"x\": "+dimXStr+",\"y\": "+dimYStr+",\"z\": "+dimZStr+"},";
			headString += "\"typeMapping\": {\"0\": {\"name\" : \"LM\"},\"1\": {\"name\": \"ADP\"},\"2\": {\"name\": \"ADPPi\"},\"3\": {\"name\": \"ATP\"},\"4\": {\"name\": \"CAP\"},\"5\": {\"name\": \"ARP23\"},\"6\": {\"name\": \"ACTA\"},\"7\": {\"name\": \"XLINK\"}}";
			headString += "},";
			headString += "\"spatialData\": {\"version\": 1,\"msgType\": 1,\"bundleStart\": 0,\"bundleSize\": "+simJSon2Steps+",\"bundleData\": [";
			jSonPW2.print(headString);
		} catch (IOException ioe) { talkln ("An error creating Simularium JSON 2 file"); }
	}
	
	public static void writeSimJSonsFrame () {
		//System.out.print("Writing JSonFile frame" + (simJSonCounter+1) + "/" + simJSonSteps + " ...");
		idCt = 0;
		String openFrameStr = "{ \"frameNumber\" : "+simJSonCounter+",\"time\" : "+String.format("%.4f",Env.simulationTime)+",\"data\" : ["; 
		String closeFrameStr = "]}";
		jSonPW.print(openFrameStr);

		for (int i=0;i<Thing.thingCt;i++) {	// all players except LM, see below
			if (Thing.theThings[i] != Thing.lmBug) { jSonPW.print(Thing.theThings[i].getJSonString()); }
		}
		
		Arp23.arpJSonIDCounter = 0;  // reset counter used in unique IDs
		for (int i=0;i<Arp23.arp23Ct;i++) {	// all ActAs that are linked
			jSonPW.print(Arp23.theArp23s[i].getJSonString());
		}
		
		for (int i=0;i<ActA.actACt;i++) {	// all ActAs that are linked
			jSonPW.print(ActA.theActAs[i].getJSonString());
		}
		
		for (int i=0;i<FilLink.filLinkCt;i++) {	// all active crosslinks
			jSonPW.print(FilLink.filLinks[i].getJSonString());
		}
		
		jSonPW.print(Thing.lmBug.getJSonString()); // Listeria is Player.thePlayers[0]... separating let's us get final comma right w/o effort
			
		jSonPW.print(closeFrameStr);
		if (simJSonCounter < simJSonSteps-1) { jSonPW.print(","); }  // comma after all frames except the last
		
		checkUniqueJSonIDs();
		simJSonCounter++;
	}
	
	public static void writeSimJSonsFrame2 () {
		if (simJSon2Counter > simJSon2Steps) { return; }  // failsafe
		idCt = 0;
		String openFrameStr = "{ \"frameNumber\" : "+simJSon2Counter+",\"time\" : "+String.format("%.4f",Env.simulationTime)+",\"data\" : ["; 
		String closeFrameStr = "]}";
		jSonPW2.print(openFrameStr);

		for (int i=0;i<Thing.thingCt;i++) {	// all players except LM, see below
			if (Thing.theThings[i] != Thing.lmBug) { jSonPW2.print(Thing.theThings[i].getJSonString()); }
		}
		
		Arp23.arpJSonIDCounter = 0;  // reset counter used in unique IDs
		for (int i=0;i<Arp23.arp23Ct;i++) {	// all ActAs that are linked
			jSonPW2.print(Arp23.theArp23s[i].getJSonString());
		}
		
		for (int i=0;i<ActA.actACt;i++) {	// all ActAs that are linked
			jSonPW2.print(ActA.theActAs[i].getJSonString());
		}
		
		for (int i=0;i<FilLink.filLinkCt;i++) {	// all active crosslinks
			jSonPW2.print(FilLink.filLinks[i].getJSonString());
		}
		
		jSonPW2.print(Thing.lmBug.getJSonString()); // Listeria is Player.thePlayers[0]... separating let's us get final comma right w/o effort
			
		jSonPW2.print(closeFrameStr);
		System.out.print("**writing JSon2Frame #" + simJSon2Counter);
		if (simJSon2Counter <= simJSon2Steps-1) { jSonPW2.print(","); System.out.print("  with comma");}  // comma after all frames except the last
		System.out.println(" **");
		checkUniqueJSonIDs();
		simJSon2Counter++;
	}
	
	
	public static void writeSimJSonPlots () {
		jSonPW.print("]},\"plotData\": {\"version\": 1,\"data\": [");
		jSonPW.print(simJSonScatterPlot("Forces On Bug", "time(s)", "pN", timeSteps, lmForces,lmForcesNames,"lines")); // #1: forces on bug
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("Active ActA Links", "time(s)", "link number", timeSteps, aveActiveLinks, "lines"));  // #2: active links
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("Tethers Broken","time(s)","tethers broken", timeSteps, linksBroken, "lines")); // #3: links broken
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("Tethers Formed","time(s)","tethers formed", timeSteps, linksFormed, "lines")); // #4: links formed
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("LM Path Position", "time(s)", "pos (microns)", timeSteps, lmPosx,"lines")); // #5
		//jSonPW.print(simJSonScatterPlot("LM xPos", "time(s)", "pos (microns)", timeSteps, lmPos,lmPosNames,"lines")); // #5+ y and z position too
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("Polymerization Near Surface", "time(s)", "monomers added", timeSteps, polyPrettyClose, "lines"));  // #6: polymerization close to bug
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("Barbed-ends Near Surface", "time(s)", "barbed-ends", timeSteps, tipsPrettyClose, "lines"));  // #7: barbed-ends close
		jSonPW.print(",");
		jSonPW.print(simJSonScatterPlot("New Arp2/3 Branches", "time(s)", "arp2/3s", timeSteps, arpsPrettyClose, "lines"));  // #8: arp2/3s close
		jSonPW.print("]}"); // close plots
	}
	
	public static void writeSimJSon2Plots () {
		jSonPW2.print("]},\"plotData\": {\"version\": 1,\"data\": [");
		jSonPW2.print(simJSonScatterPlot("Forces On Bug", "time(s)", "pN", timeSteps2, lmForces2,lmForcesNames2,"lines")); // #1: forces on bug
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("Active ActA Links", "time(s)", "link number", timeSteps2, aveActiveLinks2, "lines"));  // #2: active links
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("Tethers Broken","time(s)","tethers broken", timeSteps2, linksBroken2, "lines")); // #3: links broken
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("Tethers Formed","time(s)","tethers formed", timeSteps2, linksFormed2, "lines")); // #4: links formed
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("LM Path Position", "time(s)", "pos (microns)", timeSteps2, lmPosx2,"lines")); // #5
		//jSonPW2.print(simJSonScatterPlot("LM xPos", "time(s)", "pos (microns)", timeSteps, lmPos2,lmPosNames2,"lines")); // #5+ y and z position too
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("Polymerization Near Surface", "time(s)", "monomers added", timeSteps2, polyPrettyClose2, "lines"));  // #6: polymerization close to bug
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("Barbed-ends Near Surface", "time(s)", "barbed-ends", timeSteps2, tipsPrettyClose2, "lines"));  // #7: barbed-ends close
		jSonPW2.print(",");
		jSonPW2.print(simJSonScatterPlot("New Arp2/3 Branches", "time(s)", "arp2/3s", timeSteps2, arpsPrettyClose2, "lines"));  // #8: arp2/3s close
		jSonPW2.print("]}"); // close plots
	}
	
	public static void saveJSonPlotData () {
		if (simJSonPlotCounter > simJSonPlotSteps-1) { return; }  // failsafe
		timeSteps[simJSonPlotCounter] = Env.simulationTime;
		// #1: forces on the bug... multiplied by 1e12 to show in pN
		lmForces[0][simJSonPlotCounter] = ((double)Env.aveColForceInx/(double)Env.simJSonFreq)*1e12;
		lmForces[1][simJSonPlotCounter] = ((double)Env.aveLinkForceInx/(double)Env.simJSonFreq)*1e12;
		lmForces[2][simJSonPlotCounter] = ((double)Env.aveFricForceInx/(double)Env.simJSonFreq)*1e12;
		lmForces[3][simJSonPlotCounter] = ((double)Env.aveNormForce/(double)Env.simJSonFreq)*1e12;
		lmForces[4][simJSonPlotCounter] = ((double)Env.avePathForceWBrownianInx/(double)Env.simJSonFreq)*1e12;
		// #2:  active ActA links (average)
		aveActiveLinks[simJSonPlotCounter] = (double)Env.aveLinkCt/(double)Env.simJSonFreq;
		// #3 and #4
		linksBroken[simJSonPlotCounter] = (double)Env.cutTetherCt;
		linksFormed[simJSonPlotCounter] = (double)Env.newTetherCt;
		// #5 and #5+   listeria position
		lmPosx[simJSonPlotCounter] = Thing.lmBug.pathCoord.x;
		lmPos[0][simJSonPlotCounter] = Thing.lmBug.pathCoord.x;
		lmPos[1][simJSonPlotCounter] = Thing.lmBug.pathCoord.y;
		lmPos[2][simJSonPlotCounter] = Thing.lmBug.pathCoord.z;
		// #6: actin polymerization near the bacterial surface... not an average
		polyPrettyClose[simJSonPlotCounter] = (double)Env.polyPrettyClose;
		// #7: barbed-end near the bacterial surface (average)
		tipsPrettyClose[simJSonPlotCounter] = (double)Env.tipsPrettyClose/(double)Env.simJSonFreq;
		// #8: arp2/3s near or some other measure of arps
		arpsPrettyClose[simJSonPlotCounter] = (double)Env.newArpCt;	
		simJSonPlotCounter++;
	}
	
	public static void saveJSonPlotData2 () {
		if (simJSon2Counter > simJSon2Steps-1) { return; }  // failsafe
		timeSteps2[simJSon2Counter] = Env.simulationTime;
		// #1: forces on the bug... multiplied by 1e12 to show in pN
		lmForces2[0][simJSon2Counter] = ((double)Env.aveColForceInx2/(double)Env.simJSon2Freq)*1e12;
		lmForces2[1][simJSon2Counter] = ((double)Env.aveLinkForceInx2/(double)Env.simJSon2Freq)*1e12;
		lmForces2[2][simJSon2Counter] = ((double)Env.aveFricForceInx2/(double)Env.simJSon2Freq)*1e12;
		lmForces2[3][simJSon2Counter] = ((double)Env.aveNormForce2/(double)Env.simJSon2Freq)*1e12;
		lmForces2[4][simJSon2Counter] = ((double)Env.avePathForceWBrownianInx2/(double)Env.simJSon2Freq)*1e12;
		// #2:  active ActA links (average)
		aveActiveLinks2[simJSon2Counter] = (double)Env.aveLinkCt2/(double)Env.simJSon2Freq;
		// #3 and #4 
		linksBroken2[simJSon2Counter] = (double)Env.cutTetherCt2;
		linksFormed2[simJSon2Counter] = (double)Env.newTetherCt2;
		// #5 and #5+   listeria position
		lmPosx2[simJSon2Counter] = Thing.lmBug.pathCoord.x;
		lmPos2[0][simJSon2Counter] = Thing.lmBug.pathCoord.x;
		lmPos2[1][simJSon2Counter] = Thing.lmBug.pathCoord.y;
		lmPos2[2][simJSon2Counter] = Thing.lmBug.pathCoord.z;
		// #6: actin polymerization near the bacterial surface... not an average
		polyPrettyClose2[simJSon2Counter] = (double)Env.polyPrettyClose2;
		// #7: barbed-end near the bacterial surface (average)
		tipsPrettyClose2[simJSon2Counter] = (double)Env.tipsPrettyClose2/(double)Env.simJSon2Freq;
		// #8: arp2/3s near or some other measure of arps
		arpsPrettyClose2[simJSon2Counter] = (double)Env.newArpCt2;	
	}
	
	public static void emptyGraphsJSon() {
		jSonPW.print("]},\"plotData\": {\"version\": 1,\"data\": [");
		
		jSonPW.print("]}"); // close plots
	}
	
	public static void closeJSons() {
		if (Env.writeSimJSons) {
			writeSimJSonPlots();
			closeSimulariumJSonFile();
		}
		if (Env.writeSimJSons2) {
			writeSimJSon2Plots();
			closeSimulariumJSonFile2();
		}
		System.out.println("Finished closing JSON plots!");
	}
	
	public static void closeSimulariumJSonFile () {
		jSonPW.println("}");
	}
	
	public static void closeSimulariumJSonFile2 () {
		jSonPW2.println("}");
	}
	
	public static String simJSonHistoPlot (String title, String xAxis, String yAxis, HistogramPlus2 curHisto) {
		String scatString = "{\"layout\" : {\"title\" : \""+title+"\",\"xaxis\" : {\"title\" : \""+xAxis+"\"},\"yaxis\": {\"title\" : \""+yAxis+"\"}},";
		scatString += "\"data\" : [{\"name\" : \""+yAxis+"\",\"type\" : \"histogram\",";
		scatString += "\"x\" : ";  // opening of histogram data write
		scatString += curHisto.getBinString();
		scatString += "}]}"; // close this plot
		
		return scatString;
	}
	
	public static String simJSonHistoPlot (String title, String xAxis, String yAxis, double [] xData) {
		int steps = xData.length;
		String scatString = "{\"layout\" : {\"title\" : \""+title+"\",\"xaxis\" : {\"title\" : \""+xAxis+"\"},\"yaxis\": {\"title\" : \""+yAxis+"\"}},";
		scatString += "\"data\" : [{\"name\" : \""+yAxis+"\",\"type\" : \"histogram\",";
		scatString += "\"x\" : [";  // opening of histo write
		for (int i=0;i<steps;i++) {
			scatString += (int)xData[i]; //String.format("%.2f",xData[i]);
			if (i < steps-1) { scatString += ","; }
		}
		scatString += "]"; // closing of x variables write
		scatString += "}]}"; // close this plot
		
		return scatString;
	}
	
	public static String simJSonScatterPlot (String title, String xAxis, String yAxis, double [] xData, double [] yData, String mode) {
		int steps = xData.length;
		String scatString = "{\"layout\" : {\"title\" : \""+title+"\",\"xaxis\" : {\"title\" : \""+xAxis+"\"},\"yaxis\": {\"title\" : \""+yAxis+"\"}},";
		scatString += "\"data\" : [{\"name\" : \""+yAxis+"\",\"type\" : \"scatter\",";
		scatString += "\"x\" : [";  // opening of x variables write
		for (int i=0;i<steps;i++) {
			scatString += String.format("%.4f",xData[i]);
			if (i < steps-1) { scatString += ","; }
		}
		scatString += "],"; // closing of x variables write
		
		scatString += "\"y\" : [";	// opening of y variable write
		for (int i=0;i<steps;i++) {
			scatString += String.format("%.4f",yData[i]);
			if (i < steps-1) { scatString += ","; }
		}
		scatString += "],"; // closing of y variables write
		scatString += "\"mode\" : \""+mode+"\"}]}";
		
		return scatString;
	}
	
	public static String simJSonScatterPlot (String title, String xAxis, String yAxis, double [] xData, double [][] yData, String[] names, String mode) {
		int steps = xData.length;
		String scatString = "{\"layout\" : {\"title\" : \""+title+"\",\"xaxis\" : {\"title\" : \""+xAxis+"\"},\"yaxis\": {\"title\" : \""+yAxis+"\"}},";
		scatString += "\"data\" : [";
		// plots loop
		for (int j=0;j<names.length;j++) {
			scatString += "{\"name\" : \""+names[j]+"\",\"type\" : \"scatter\",";
			scatString += "\"x\" : [";  // opening of x variables write
			for (int i=0;i<steps;i++) {
				scatString += String.format("%.4f",xData[i]);
				if (i < steps-1) { scatString += ","; }
			}
			scatString += "],"; // closing of x variables write
			
			scatString += "\"y\" : [";	// opening of y variable write
			for (int i=0;i<steps;i++) {
				scatString += String.format("%.4f",yData[j][i]);
				if (i < steps-1) { scatString += ","; }
			}
			scatString += "],"; // closing of y variables write
			scatString += "\"mode\" : \""+mode+"\"}";
			if (j < names.length-1) { scatString += ","; }
		}
		scatString += "]}";	// close this plot
		
		return scatString;
	}
	
	public static void recalcJSonValues() {
		simJSonSteps = (int)(Env.simJSonSavesPerSec*Env.runTime.getValue()+1);
		simJSon2Steps = (int)(Env.simJSon2SavesPerSec*(Env.simJSon2Stop-Env.simJSon2Start)+1);
		simJSonPlotSteps = (int)(Env.simJSonPlotSavesPerSec*Env.runTime.getValue()+1);	// this is how many frames we'll write in JSon serialization

		Env.simJSonFreq = (int)(Math.ceil((1.0/Env.deltaT.getValue())*(1.0/Env.simJSonSavesPerSec))); 	// number of integration steps between Simularium jSon saves
		Env.simJSonPlotFreq = (int)(Math.ceil((1.0/Env.deltaT.getValue())*(1.0/Env.simJSonPlotSavesPerSec))); 	// number of integration steps between Simularium jSon saves
		Env.simJSon2Freq = (int)(Math.ceil((1.0/Env.deltaT.getValue())*(1.0/Env.simJSon2SavesPerSec))); 	// number of integration steps between Simularium jSon saves for second file
		Env.simJSon2StartCounting = (int)(Env.simJSon2Start/Env.deltaT.getValue() - Env.simJSon2Freq);  // integration step at which to reset counters for valid first counting
	
		// data structures for storing values for Simularium JSON
		timeSteps = new double[simJSonPlotSteps];
		lmForces = new double[5][simJSonPlotSteps]; // #1: forces on bug
		aveActiveLinks = new double[simJSonPlotSteps]; // #2: averaged active ActA links
		// #3 and #4 are histograms
		linksBroken = new double[simJSonPlotSteps];
		linksFormed = new double[simJSonPlotSteps];
		lmPosx = new double[simJSonPlotSteps]; // #5: bacterial position along path
		lmPos = new double[3][simJSonPlotSteps]; // #5+: y and z too, if needed
		
		polyPrettyClose = new double[simJSonPlotSteps];	// #6: polymerization close to the bacterial surface
		tipsPrettyClose = new double[simJSonPlotSteps];	// #7: barbed-ends close to the bacterial surface
		arpsPrettyClose = new double[simJSonPlotSteps];	// #8: arps close
		
		// data structures for storing values for Simularium JSON, second JSon file
		timeSteps2 = new double[simJSon2Steps];
		lmForces2 = new double[5][simJSon2Steps]; // #1: forces on bug
		aveActiveLinks2 = new double[simJSon2Steps]; // #2: averaged active ActA links
		// #3 and #4 are histograms
		linksBroken2 = new double[simJSon2Steps];
		linksFormed2 = new double[simJSon2Steps];
		lmPosx2 = new double[simJSon2Steps]; // #5: bacterial position along path
		lmPos2 = new double[3][simJSon2Steps]; // #5+: y and z too, if needed
		
		polyPrettyClose2 = new double[simJSon2Steps];	// #6: polymerization close to the bacterial surface
		tipsPrettyClose2 = new double[simJSon2Steps];	// #7: barbed-ends close to the bacterial surface
		arpsPrettyClose2 = new double[simJSon2Steps];	// #8: arps close
		
	}
	
	public static void talkln (String info) {
		if (Env.remote & Env.logFiles) {
			//if (!outFileMade) { mkOutFile(); }
			outPW.println(info);
		} else {
			System.out.println(info);
		}
	}
	
	public static void talk (String info) {
		if (Env.remote & Env.logFiles) {
			//if (!outFileMade) { mkOutFile(); }
			outPW.print(info);
		} else {
			System.out.print(info);
		}
	}
}
