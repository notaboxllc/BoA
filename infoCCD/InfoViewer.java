/** InfoViewer.java
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

public class InfoViewer extends JFrame implements ActionListener {
	
	private JEditorPane EditorPane;
	private JButton BackButton;
	private JButton ForwardButton;
	private JButton homeButton;
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
	static JToolBar navBar;
	
	static URL startURL;
	
	
	/** Simple constructor
	 @param URL - The url to initialize the Editor pane with
	 */
	public InfoViewer() {
		
		super("Instructions");
		
		// Make main pane
		MainPane = new JPanel();
		setContentPane(MainPane);
		MainPane.setLayout(new BorderLayout());
		
		// Make the top pane
		TopPane = new JPanel();
		TopPane.setLayout(new BoxLayout(TopPane,BoxLayout.Y_AXIS));
		TopPane.setBackground(backColor);
		TopPane.setForeground(frontColor);
		
		ImagePane = new JPanel(new FlowLayout());
		ImagePane.setBackground(Color.black);
		Icon ccdlogo = new ImageIcon(rG.getResource("Pics/ccdlogo.png"));
		Icon ccdheader = new ImageIcon(rG.getResource("Pics/ccdhead.png")); 
		ImagePane.add(new JLabel(ccdlogo));
		ImagePane.add(new JLabel(ccdheader));
		
		TopPane.add(ImagePane);
		
		// Make the navigation bar
		makeNavBar();
		
		TopPane.add(navBar);
		MainPane.add(TopPane,BorderLayout.NORTH);
		
		// Make the editor pane
		try {
			startURL = rG.getResource(Info.instructionsFile);
			
			EditorPane = new JEditorPane(startURL);
			EditorPane.setBorder(BorderFactory.createLineBorder(backColor,7));
			//EditorPane.setBackground(backColor);
			//EditorPane.setForeground(frontColor);
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
	
	public void goHome() {
		if (curURLPoint.equals(homeURLPoint)) { return; }
		homeURLPoint.setLinks(curURLPoint);
		curURLPoint = homeURLPoint;
		try {
			EditorPane.setPage(curURLPoint.url);
			LocationField.setText(curURLPoint.url.toString());
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
		setActiveBrowserButtons();
	}
	
	public void setActiveBrowserButtons() {
		boolean backEnabled = !(curURLPoint.lastPoint.equals(URLPoint.endGhost));
		BackButton.setEnabled(backEnabled);
		
		boolean forwardEnabled = !(curURLPoint.nextPoint.equals(URLPoint.endGhost));
		ForwardButton.setEnabled(forwardEnabled);
		
		boolean atHome = (curURLPoint.equals(homeURLPoint));
		homeButton.setEnabled(!atHome);
	}

  
	
}
