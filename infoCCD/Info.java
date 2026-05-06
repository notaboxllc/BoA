
/* Info ... store author, copyright, bundle-type, etc */

/*  <InfoCCD - copyright notice, help viewer, parameter loader, console utilities for any Java program>
    Copyright (C) <2008>  <Jonathan B. Alberts>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package infoCCD;

import java.awt.Menu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class Info implements ActionListener { 
	
	// **** Author Info, etc  ******
	public static String appName = "BOA (Boxes Of Actin)";
	public static String appIcon = "Pics/boa.png";
	public static String appParamID = "<BOA>";
	public static String author = "Jonathan B. Alberts";
	public static String copyrightYear = "2009";
	public static String copyrightFile = "COPYING";
	public static String instructionsFile = "Instructions/index.html";
	public static String authorEmail = "jalberts@u.washington.edu";
	public static String website = "www.celldynamics.org";
	public static String logoIcon = "Pics/ccdlogoSmall.png";
	
	// for Parameter Loading Class
	public static String paramLoaderImage = "Pics/boaSmall.png";
	public static String localParamLoc = "ParameterFiles/index.html";
	public static String webParamLoc = "http://www.celldynamics.org/SimParamFiles/BOA/index.html";
	public static String startingParamPage = "ParameterFiles/startPage.txt";

	
	// **** For Bundling Apps and EXEs *****
	public static final int JUSTJAVA = 0;
	public static final int FROMAPP = 1;
	public static final int FROMEXE = 2;
	public static final int runType = JUSTJAVA;
	public static String resourceLocStr;
	
	// **** the various classes
	public static Console console;
	public static CopyingNotice copyNote;
	public static InfoViewer infoViewer;
	public static ParamLoader paramLoader;
	
	// *** for the Info menu ***
	public static Menu infoMenu;
	
	public Info (boolean consoleOn, boolean infoViewerOn, boolean paramLoaderOn) {
		infoMenu = new Menu("Info");
		// make console first to catch errors, etc
		if (consoleOn) { //make console
			console = new Console(); 
			infoMenu.add("Console");
		} 				
		
		copyNote = new CopyingNotice();
		copyNote.runAsSplash();
		
		if (infoViewerOn) { //make infoviewer
			infoViewer = new InfoViewer(); 
			infoMenu.add("Instructions");
		}		
		
		if (paramLoaderOn) { //make paramloader
			paramLoader = new ParamLoader(); 
			infoMenu.add("Parameter Loader");
		}		
		
		infoMenu.add("Copying Notice");
		infoMenu.addActionListener(this);
		
	}
	
	public void actionPerformed( ActionEvent event ) {
		String arg = event.getActionCommand( );
		
		if ( arg.equals("Console") ) { console.setVisible(true); }
		if ( arg.equals("Copying Notice") ) { copyNote.setVisible(true); }
		if ( arg.equals("Instructions") ) { infoViewer.setVisible(true); }
		if ( arg.equals("Parameter Loader") ) { paramLoader.setVisible(true); }
	}
	
    public static boolean pageIsParams (String allLinesStr) {
    	int bolIndex = 0;	// beginning-of-line index
		int eolIndex = 0;	// end-of-line index
		String curLine;
		String endLine = "\n";//System.getProperty("line.separator");
		int finalIndex = allLinesStr.lastIndexOf(endLine);	// we stop at last occurrence of end-of-line
		while (bolIndex < finalIndex) {
			eolIndex = allLinesStr.indexOf(endLine,bolIndex);
	    	curLine = allLinesStr.substring(bolIndex,eolIndex);
	    	if (curLine.startsWith(Info.appParamID)) { return true; }
			bolIndex = eolIndex+1; // next line
		}
    	return false;
    }
    
	public static boolean pageIsParams (File paramFile) {
		if ( ! paramFile.exists() || ! paramFile.canRead() ) {
			System.out.println ("Can't find or access " + paramFile.getAbsolutePath() + " ... check the file path and name");
			return false;
		} else {
			try {
				FileReader fileR = new FileReader(paramFile);
				BufferedReader in = new BufferedReader(fileR);
				String curLine = in.readLine();
				while (curLine != null) {
					if (curLine.startsWith(Info.appParamID)) { return true; }
					curLine = in.readLine(); // next line
					
				}
			} catch (IOException ioe) { System.out.println ("An error trying to check parameter file"); }
		}
		return false;
	}
    
	public static void loadParams (String paramString) {
		boxOfActin.FileOps.loadParamsFromViewer(paramString);
	}
	
	public static String getKeyLine () {
		return appParamID;
	}
	
	public static void setConsoleVisible(boolean vis) {
		if (console != null) { console.setVisible(vis); }
	}
}