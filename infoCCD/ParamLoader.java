/** ParamLoader.java
 A simple viewer for browsing help files and loading parameter sets.
 @author WJS... editor/mucker JBA
 */

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

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import javax.swing.event.*;
import javax.swing.*;

public class ParamLoader extends JFrame implements ActionListener {
	
	private JEditorPane EditorPane;
	private JButton BackButton;
	private JButton ForwardButton;
	private JButton homeButton;
	private JButton OpenParamsButton;
	private JButton LoadParamsButton;
	private JButton	LocalParamsButton;
	private JButton WebParamsButton;
	private JTextField LocationField;
	/** The main pane holds all */
	private JPanel                      MainPane;
	/** The top pane holds all */
	private JPanel                      TopPane;
	/** The main pane holds all */
	private JPanel                      ImagePane;
	
	private URLPoint curURLPoint;
	private URLPoint homeURLPoint;
	
	static Font headerFont = new Font(null,Font.PLAIN,12);
	static Font headerFontBold = new Font (null,Font.BOLD,12);
	static Color backColor = Color.black;
	static Color frontColor = Color.white;
	static final Dimension SCREEN_SIZE = Toolkit.getDefaultToolkit().getScreenSize();
	static ResourceGetter rG = new ResourceGetter(Info.runType);
	static JToolBar navBar,linkBar;
	static JFileChooser loadChooser;
	
	static URL startURL;
	
	
	/** Simple constructor
	 @param URL - The url to initialize the Editor pane with
	 */
	public ParamLoader() {
		
		super("Parameter Browser and Editor");
		
		// Make main pane
		MainPane = new JPanel();
		setContentPane(MainPane);
		MainPane.setLayout(new BorderLayout());
		
		// Make the top pane
		TopPane = new JPanel();
		TopPane.setLayout(new BoxLayout(TopPane,BoxLayout.Y_AXIS));
		TopPane.setBackground(backColor);
		TopPane.setForeground(frontColor);
		
		// Make the navigation bar
		makeNavBar();
		makeLinkBar();
		
		TopPane.add(linkBar);
		TopPane.add(navBar);
		
		MainPane.add(TopPane,BorderLayout.NORTH);
		
		// Make the editor pane
		try {
				
			startURL = rG.getResource(Info.startingParamPage);
			EditorPane = new JEditorPane(startURL);
			EditorPane.setBorder(BorderFactory.createLineBorder(backColor,7));
			EditorPane.setBackground(backColor);
			EditorPane.setForeground(frontColor);
			setURL(startURL);
			EditorPane.setEditable(false);
			
			// Add a hyperlink listener to handle clicks on links
			EditorPane.addHyperlinkListener(new HyperlinkListener() {
						public void hyperlinkUpdate(HyperlinkEvent e) {
							if (e.getEventType()==HyperlinkEvent.EventType.ENTERED)
								setCursor(new Cursor(Cursor.HAND_CURSOR));
							if (e.getEventType()==HyperlinkEvent.EventType.EXITED)
								setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
							if (e.getEventType()==HyperlinkEvent.EventType.ACTIVATED) {
								try {
									setURL(e.getURL());
								} catch (Exception ex) {
									System.out.println("Failed to open url - "+e.getURL().getPath());
								}
							}
						}
					});
		} catch (IOException ioex) {
			System.out.println("File not found - "+ startURL.toString());
		}
		
		JScrollPane scrollPane = new JScrollPane(EditorPane);
		MainPane.add(scrollPane,BorderLayout.CENTER);
		
		setSize(700,700);
		this.setLocation(SCREEN_SIZE.width/2 - this.getSize().width/2,SCREEN_SIZE.height/2 - this.getSize().height/2);
		this.setVisible(false);
		
	}
	
	public void actionPerformed( ActionEvent event ) {
		String arg = event.getActionCommand( );
		
		if ( arg.equals("Home") ) { goHome(); }
		
		if ( arg.equals("Open") ) {
			URL loadMeURL = getFileToLoad("Choose a parameter file...",this);
			if (loadMeURL != null) {
				setURL(loadMeURL);
			}
		}
		
		if ( arg.equals("Load") ) { Info.loadParams(EditorPane.getText()); }
		if ( arg.equals("LocalParams") ) { setURL(rG.getResource(Info.localParamLoc)); }
		if ( arg.equals("WebParams") ) { setURL(Info.webParamLoc); }
		
		
		if ( arg.equals("Back") ) { backOne(); }
		if ( arg.equals("Forward") ) { forwardOne(); }
		if ( arg.equals("LocFieldChange") ) { setURL(LocationField.getText()); }
		
	}
	
	public void makeNavBar () {
		navBar = new JToolBar();
		navBar.setBackground(backColor);
		navBar.setForeground(frontColor);
		navBar.setFloatable(false);
		
		// Back button moves us back to previous page
		Icon lastIcon = new ImageIcon(rG.getResource("Pics/arrowLast.png"));
		BackButton = new JButton(lastIcon);
		BackButton.setActionCommand("Back");
		BackButton.setToolTipText("Back");
		BackButton.addActionListener(this);
		navBar.add(BackButton);
		
		// Forward button moves us forward to a page if back was used
		Icon nextIcon = new ImageIcon(rG.getResource("Pics/arrowNext.png"));
		ForwardButton = new JButton(nextIcon);
		ForwardButton.setActionCommand("Forward");
		ForwardButton.setToolTipText("Forward");
		ForwardButton.addActionListener(this);
		navBar.add(ForwardButton);
		
		// Location field
		LocationField = new JTextField(" :) ");
		LocationField.setActionCommand("LocFieldChange");
		LocationField.setFont(headerFont);
		LocationField.setBackground(backColor);
		LocationField.setForeground(frontColor);
		LocationField.addActionListener(this);
		
		// Home button... like it sounds
		Icon homeIcon = new ImageIcon(rG.getResource("Pics/homeIcon.png"));
		homeButton = new JButton(homeIcon);
		homeButton.setActionCommand("Home");
		homeButton.setToolTipText("Home");
		homeButton.addActionListener(this);
		navBar.add(homeButton);
		
		navBar.add(LocationField);
	}
	
	public void makeLinkBar () {
		linkBar = new JToolBar();
		linkBar.setBorder(BorderFactory.createLineBorder(backColor,4));
		linkBar.setBackground(backColor);
		linkBar.setForeground(frontColor);
		linkBar.setFloatable(false);
		linkBar.setAlignmentX(JToolBar.LEFT_ALIGNMENT);
		
		ImagePane = new JPanel(new FlowLayout());
		ImagePane.setBackground(Color.black);
		Icon topImage = new ImageIcon(rG.getResource(Info.paramLoaderImage));
		JLabel topImageLabel = new JLabel(topImage);
		ImagePane.add(topImageLabel);
		
		// OpenParamsButton ... browse for parameter files
		OpenParamsButton = new JButton(" <OPEN> ");
		OpenParamsButton.setFont(headerFontBold);
		OpenParamsButton.setActionCommand("Open");
		OpenParamsButton.setToolTipText("Use this button to browse for parameter files");
		OpenParamsButton.addActionListener(this);
		
		// LoadParamsButton will check if actually a parameter file and if so will load values to program
		LoadParamsButton = new JButton(" <LOAD> ");
		LoadParamsButton.setFont(headerFontBold);
		LoadParamsButton.setActionCommand("Load");
		LoadParamsButton.setToolTipText("Use this button to load the parameter file displayed below");
		LoadParamsButton.addActionListener(this);
		
		// LocalParamsButton will check if actually a parameter file and if so will load values to program
		LocalParamsButton = new JButton(" <Local Parameter Files> ");
		LocalParamsButton.setFont(headerFont);
		LocalParamsButton.setActionCommand("LocalParams");
		LocalParamsButton.setToolTipText("Show local parameter files");
		LocalParamsButton.addActionListener(this);
		
		// WebParamsButton will check if actually a parameter file and if so will load values to program
		WebParamsButton = new JButton(" <Online Parameter Files> ");
		WebParamsButton.setFont(headerFont);
		WebParamsButton.setActionCommand("WebParams");
		WebParamsButton.setToolTipText("Show online parameter files");
		WebParamsButton.addActionListener(this);
		
		linkBar.add(topImageLabel);
		if (Info.localParamLoc != null) { linkBar.add(LocalParamsButton); }
		linkBar.add(OpenParamsButton);
		linkBar.add(LoadParamsButton);
		if (Info.webParamLoc != null) { linkBar.add(WebParamsButton); }
		
		
	}
	
 
	public void goHome() {
		if (curURLPoint.equals(homeURLPoint)) { return; }
		homeURLPoint.setLinks(curURLPoint);
		curURLPoint = homeURLPoint;
		try {
			EditorPane.setPage(curURLPoint.url);
			LocationField.setText(curURLPoint.url.toString());
			setGUIIfParamsFile();
			setActiveBrowserButtons();
		} catch (Exception ex) {
			System.out.println("InfoViewer exception.. can't load home page");
		}
	}
	
	public void backOne() {
		if (curURLPoint.lastPoint.equals(URLPoint.endGhost)) {
			System.out.println("Nothing back there!");
			return;
		} else {
			//System.out.println("In backOne... curURLPoint = " + curURLPoint.url.toString() + " : will go back to " + curURLPoint.lastPoint.url.toString());
			curURLPoint = curURLPoint.lastPoint;
		}
		try {
			EditorPane.setPage(curURLPoint.url);
			LocationField.setText(curURLPoint.url.toString());
			setGUIIfParamsFile();
			setActiveBrowserButtons();
		} catch (Exception ex) {
			System.out.println("InfoViewer exception.. page not found from back button");
		}
	}
	
	public void forwardOne() {
		if (curURLPoint.nextPoint.equals(URLPoint.endGhost)) {
			System.out.println("Nothing next there!");
			return;
		} else {
			curURLPoint = curURLPoint.nextPoint;
		}
	
		try {
			EditorPane.setPage(curURLPoint.url);
			LocationField.setText(curURLPoint.url.toString());
			setGUIIfParamsFile();
			setActiveBrowserButtons();
		} catch (Exception ex) {
			System.out.println("InfoViewer exception.. nothing next there");
		}
	}
	
	 /** Sets the page contents according to the URL
	 @param String - url string.
	 @author WJS
	 */
	public void setURL(String urlString) {
		
		try {
		  URL url = new URL(urlString);
			try {
				EditorPane.setPage(url);
				LocationField.setText(urlString);
				setURLPoint(url);
			} catch (Exception ex) {
				System.out.println("HTMLViewer - page not found: "+urlString);
			}
		} catch (MalformedURLException mue) {
			System.out.println("HTMLViewer - malformed URL: "+urlString);
		}
		
	}
	
 /** Sets the page contents according to the URL
	 @param URL - the url object.
	 @author WJS
	 */
	public void setURL(URL url) {
		
		try {
			EditorPane.setPage(url);
			LocationField.setText(url.toString());
			setURLPoint(url);
		} catch (Exception ex) {
			System.out.println("HTMLViewer - page not found");
		}
		
	}
	
	
	public void setURLPoint (URL newURL) {
		if (curURLPoint != null) {	// don't set links for first URLPoint
			if (! (curURLPoint.url.toString() == newURL.toString())) {	// if new URL not where we already are
				curURLPoint = new URLPoint(newURL, curURLPoint);
			}
		} else {
			homeURLPoint = new URLPoint(newURL);
			curURLPoint = homeURLPoint;
		}
		setGUIIfParamsFile();
		setActiveBrowserButtons();
	}
	
	public void setGUIIfParamsFile() {
		boolean isParams = Info.pageIsParams(EditorPane.getText());
		LoadParamsButton.setEnabled(isParams);
		EditorPane.setEditable(isParams);
	}
	
	public void setActiveBrowserButtons() {
		boolean backEnabled = !(curURLPoint.lastPoint.equals(URLPoint.endGhost));
		BackButton.setEnabled(backEnabled);
		
		boolean forwardEnabled = !(curURLPoint.nextPoint.equals(URLPoint.endGhost));
		ForwardButton.setEnabled(forwardEnabled);
		
		boolean atHome = (curURLPoint.equals(homeURLPoint));
		homeButton.setEnabled(!atHome);
	}
    
	public static URL getFileToLoad (String dialogTitle, JFrame parent) {
		File file = null;
		URL fileURL = null;
		if (loadChooser == null) { loadChooser = new JFileChooser(); } else { loadChooser.setVisible(true); }
		loadChooser.setDialogTitle(dialogTitle);
		loadChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		int fileQuery = loadChooser.showOpenDialog(parent);
		if (fileQuery == JFileChooser.APPROVE_OPTION) {
            file = loadChooser.getSelectedFile();
            try {
            	fileURL = file.toURL();
            } catch (MalformedURLException mfue) { return null; }  
        } else {
            System.out.println ("Load cancelled by user");
            return null;
        }
        return fileURL;
	}
  
	
}
