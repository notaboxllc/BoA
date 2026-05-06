//
// A simple Java Console for your application (Swing version)
// Requires Java 1.1.5 or higher
//
// Disclaimer the use of this source is at your own risk. 
//
// Permision to use and distribute into your own applications
//
// RJHM van den Bergh , rvdb@comweb.nl

// Modified by JBA jalberts@u.washington.edu


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

import java.io.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class Console extends WindowAdapter implements WindowListener, ActionListener, ChangeListener, Runnable
{
	private JFrame frame;
	private JTextArea textArea;
	private JScrollPane scrollPane;
	private JScrollBar vertBar;
	private Thread reader;
	private Thread reader2;
	private boolean quit;
	static Color backColor = Color.black;
	static Color frontColor = Color.white;
					
	private final PipedInputStream pin=new PipedInputStream(); 
	private final PipedInputStream pin2=new PipedInputStream(); 

	Thread errorThrower; // just for testing (Throws an Exception at this Console
	
	public Console()
	{
		// create all components and add them
		frame=new JFrame("Java Console");
		Dimension screenSize=Toolkit.getDefaultToolkit().getScreenSize();
		Dimension frameSize=new Dimension(800,300);
		int x=(int)(0);
		int y=(int)(500);
		frame.setBounds(x,y,frameSize.width,frameSize.height);
		
		textArea=new JTextArea();
		textArea.setBorder(BorderFactory.createLineBorder(backColor,7));
		textArea.setBackground(backColor);
		textArea.setForeground(frontColor);
		textArea.setEditable(false);
		textArea.setLineWrap(true);
		JButton clearButton=new JButton("clear");
		
		JToolBar bottomPanel = new JToolBar();
		bottomPanel.setFloatable(false);
		bottomPanel.setBackground(backColor);
		bottomPanel.add(clearButton);
		
		scrollPane = new JScrollPane(textArea);
		scrollPane.getViewport().addChangeListener(this);
		vertBar = scrollPane.getVerticalScrollBar();
		
		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(scrollPane,BorderLayout.CENTER);
		frame.getContentPane().add(bottomPanel,BorderLayout.SOUTH);
		frame.setVisible(false);		
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		
		frame.addWindowListener(this);		
		clearButton.addActionListener(this);
		
		try
		{
			PipedOutputStream pout=new PipedOutputStream(this.pin);
			System.setOut(new PrintStream(pout,true)); 
		} 
		catch (java.io.IOException io)
		{
			textArea.append("Couldn't redirect STDOUT to this console\n"+io.getMessage());
		}
		catch (SecurityException se)
		{
			textArea.append("Couldn't redirect STDOUT to this console\n"+se.getMessage());
	    } 
		
		try 
		{
			PipedOutputStream pout2=new PipedOutputStream(this.pin2);
			System.setErr(new PrintStream(pout2,true));
		} 
		catch (java.io.IOException io)
		{
			textArea.append("Couldn't redirect STDERR to this console\n"+io.getMessage());
		}
		catch (SecurityException se)
		{
			textArea.append("Couldn't redirect STDERR to this console\n"+se.getMessage());
	    } 		
			
		quit=false; // signals the Threads that they should exit
				
		// Starting two seperate threads to read from the PipedInputStreams				
		//
		reader=new Thread(this);
		reader.setDaemon(true);	
		reader.start();	
		//
		reader2=new Thread(this);	
		reader2.setDaemon(true);	
		reader2.start();
				
	}
	
	public synchronized void windowClosed(WindowEvent evt)
	{

	}		
		
	public synchronized void windowClosing(WindowEvent evt)
	{
		this.setVisible(false);
	}
	
	public synchronized void actionPerformed(ActionEvent evt)
	{
		textArea.setText("");
	}

	public synchronized void run()
	{
		try
		{			
			while (Thread.currentThread()==reader)
			{
				try { this.wait(100);}catch(InterruptedException ie) {}
				if (pin.available()!=0)
				{
					String input=this.readLine(pin);
					textArea.append(input);
				}
				if (quit) return;
			}
		
			while (Thread.currentThread()==reader2)
			{
				try { this.wait(100);}catch(InterruptedException ie) {}
				if (pin2.available()!=0)
				{
					String input=this.readLine(pin2);
					textArea.append(input);
				}
				if (quit) return;
			}			
		} catch (Exception e)
		{
			textArea.append("\nConsole reports an Internal error.");
			textArea.append("The error is: "+e);			
		}

	}
	
	public synchronized String readLine(PipedInputStream in) throws IOException
	{
		String input="";
		do
		{
			int available=in.available();
			if (available==0) break;
			byte b[]=new byte[available];
			in.read(b);
			input=input+new String(b,0,b.length);														
		}while( !input.endsWith("\n") &&  !input.endsWith("\r\n") && !quit);
		return input;
	}	
		
	public static void main(String[] arg)
	{
		new Console(); // create console with not reference	
	}	
	
	public void setVisible (boolean visibleState) {
		frame.setVisible(visibleState);
	}

	public void stateChanged(ChangeEvent arg0) {
		if (! vertBar.getValueIsAdjusting()) {
			vertBar.setValue(vertBar.getMaximum());	
		}
	}
}