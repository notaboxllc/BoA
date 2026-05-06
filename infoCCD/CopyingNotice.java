/* 	CopyingNotice... the name says it all */

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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.Timer;


public class CopyingNotice extends JFrame {
	
	static JEditorPane theEditorPane;
	static JScrollPane theScrollPane;
	static JPanel theProgramPane,theIconPane,theTopPane;
	static JLabel theCCDLabel;
	static JTextPane theDescription;
	static JTextField theTopBar;
	static JTextField theProgramTitle;
	static JTextField theWebsite;
	static JTextField theEmail;
	static JTextField theCopyright;
	static JTextField theLicense;
	static JTextField theSeparator;
	static String preSpace = "  ";
	static final Dimension SCREEN_SIZE = Toolkit.getDefaultToolkit().getScreenSize();
	static ResourceGetter rG = new ResourceGetter(Info.runType);
	static Color backColor = Color.black;
	static Color frontColor = Color.white;
	static Dimension normDim = new Dimension(600,280);
	static Dimension splashDim = new Dimension(600,280);
	Timer splashTimer;

	public CopyingNotice () {
		makeProgramInfo();
		theProgramPane = new JPanel(new GridLayout(7,1));
		theProgramPane.setBackground(backColor);
		theProgramPane.add(theTopBar);
		theProgramPane.add(theProgramTitle);
		theProgramPane.add(theCopyright);
		theProgramPane.add(theEmail);
		theProgramPane.add(theCCDLabel);
		//theProgramPane.add(theWebsite);
		theProgramPane.add(theLicense);
		theProgramPane.add(theSeparator);
		
		theIconPane = new JPanel();
		theIconPane.setBackground(backColor);
		
		Icon appIcon = new ImageIcon(rG.getResource(Info.appIcon)); 
		theIconPane.add(new JLabel(appIcon));
	
		theTopPane = new JPanel();
		theTopPane.setLayout(new BoxLayout(theTopPane,BoxLayout.X_AXIS));
		theTopPane.setBackground(backColor);
		theTopPane.setForeground(frontColor);
		theTopPane.add(theIconPane);
		theTopPane.add(theProgramPane);

		this.getContentPane().add(theTopPane,BorderLayout.NORTH);
		
		try {
			theEditorPane = new JEditorPane(rG.getResource(Info.copyrightFile));
			theEditorPane.setBackground(backColor);
			theEditorPane.setForeground(frontColor);
		} catch (IOException ioe) { System.out.println("IOException trying to get " + Info.copyrightFile); }
		
		theScrollPane = new JScrollPane(theEditorPane);
		theScrollPane.setBackground(backColor);
		
		
		this.getContentPane().add(theScrollPane,BorderLayout.CENTER);
		this.setSize(normDim);
		this.setLocation(SCREEN_SIZE.width/2 - this.getSize().width/2,SCREEN_SIZE.height/3 - this.getSize().height/2);
		this.setTitle("Copyright Notice");
		
	}
	
	public static void makeProgramInfo() {
		theTopBar = new JTextField(" ");
		theTopBar.setBackground(backColor);
		theTopBar.setForeground(frontColor);
		theTopBar.setBorder(BorderFactory.createEmptyBorder());
		
		theProgramTitle = new JTextField(Info.appName);
		theProgramTitle.setBackground(backColor);
		theProgramTitle.setForeground(frontColor);
		theProgramTitle.setBorder(BorderFactory.createEmptyBorder());
		
		theCopyright = new JTextField("Copyright © " + Info.copyrightYear + " " + Info.author);
		theCopyright.setBackground(backColor);
		theCopyright.setForeground(frontColor);
		theCopyright.setBorder(BorderFactory.createEmptyBorder());
		
		theEmail = new JTextField(Info.authorEmail);
		theEmail.setBackground(backColor);
		theEmail.setForeground(frontColor);
		theEmail.setBorder(BorderFactory.createEmptyBorder());
		
		Icon ccdLogoSmall = new ImageIcon(rG.getResource(Info.logoIcon));
		theCCDLabel = new JLabel(Info.website,ccdLogoSmall,JLabel.LEFT);
		theCCDLabel.setBackground(backColor);
		theCCDLabel.setForeground(frontColor);
		theCCDLabel.setBorder(BorderFactory.createEmptyBorder());
		
		theWebsite = new JTextField(Info.website);
		theWebsite.setBackground(backColor);
		theWebsite.setForeground(frontColor);
		theWebsite.setBorder(BorderFactory.createEmptyBorder());
		
		theLicense = new JTextField("Released as free software under the Gnu GPLv3 license");
		theLicense.setBackground(backColor);
		theLicense.setForeground(frontColor);
		theLicense.setBorder(BorderFactory.createEmptyBorder());
		
		theSeparator = new JTextField(" ");
		theSeparator.setBackground(backColor);
		theSeparator.setForeground(frontColor);
		theSeparator.setBorder(BorderFactory.createEmptyBorder());
		
		theTopBar.setEditable(false);
		theProgramTitle.setEditable(false);
		theCopyright.setEditable(false);
		theWebsite.setEditable(false);
		theLicense.setEditable(false);
		theSeparator.setEditable(false);
	}
	
	public void runAsSplash() {
		int delay = 8000; //milliseconds
		ActionListener taskPerformer = new ActionListener() {
			public void actionPerformed(ActionEvent evt) {
				setVisible(false);
				setSize(normDim);
				setAlwaysOnTop(false);
				splashTimer.stop();
		    }
		};
		splashTimer = new Timer(delay, taskPerformer);
		setSize(splashDim);
		setVisible(true);
		setAlwaysOnTop(true);
		splashTimer.start();
	}
	
    public void addImage(JPanel cp, String url)  {
    	try {
    		cp.add(new JLabel(new ImageIcon(new URL(url))));
    	} catch (MalformedURLException mfue) { System.out.println ("Can't find application icon image " + Info.appIcon); }
    }	
		
}
