package boxOfActin;
/*
 * InertGuiHead... formating for menu headers
 */

import java.awt.*;
import java.awt.event.*;
import java.lang.Math;
import javax.swing.*;
import javax.swing.event.*;

import java.text.*;
import java.util.*;

public class InertGuiHead {
	static final int CKBOX_OFF = 0;
	static final int CKBOX_LEFT = 1;
	static final int CKBOX_MID = 2;
	static final int CKBOX_RIGHT = 3;
	JLabel label1,label2,label3;
	JCheckBox ckBox = new JCheckBox();
	int ckBoxPos = CKBOX_OFF;
	
	public InertGuiHead (String pos1, String pos2, String pos3) {
		label1 = new JLabel(pos1);
		label2 = new JLabel(pos2);
		label3 = new JLabel(pos3);

		setAppearances();
	}
	
	public InertGuiHead (JCheckBox ckBox, int ckBoxPos) {
		this.ckBox = ckBox;
		this.ckBoxPos = ckBoxPos;
		
		label1 = new JLabel("");
		label2 = new JLabel("");
		label3 = new JLabel("");
		setAppearances();
	}
	
	public InertGuiHead (JCheckBox ckBox, int ckBoxPos, String pos1, String pos2, String pos3) {
		this.ckBox = ckBox;
		this.ckBoxPos = ckBoxPos;
		
		label1 = new JLabel(pos1);
		label2 = new JLabel(pos2);
		label3 = new JLabel(pos3);
		setAppearances();
	}
	
	public void setAppearances() {
		label1.setFont(Env.headFont);
		label1.setHorizontalAlignment(JLabel.LEFT);
		label1.setForeground(Env.controlForeColor);
		
		label2.setFont(Env.headFont);
		label2.setHorizontalAlignment(JLabel.CENTER);
		label2.setForeground(Env.controlForeColor);
		
		label3.setFont(Env.headFont);
		label3.setHorizontalAlignment(JLabel.RIGHT);
		label3.setForeground(Env.controlForeColor);
		
		ckBox.setFont(Env.headFont);
		ckBox.setHorizontalAlignment(JLabel.LEFT);
		ckBox.setForeground(Env.controlForeColor);
		ckBox.setBackground(Env.controlBackColor);
	}
	
	public void addToPanel (JPanel thePanel) {
		switch (ckBoxPos) {
		case CKBOX_OFF:
			thePanel.add(label1);
			thePanel.add(label2);
			thePanel.add(label3);
			break;
		case CKBOX_LEFT:
			thePanel.add(ckBox);
			thePanel.add(label2);
			thePanel.add(label3);
			break;
		case CKBOX_MID:
			thePanel.add(label1);
			thePanel.add(ckBox);
			thePanel.add(label3);
			break;
		case CKBOX_RIGHT:
			thePanel.add(label1);
			thePanel.add(label2);
			thePanel.add(ckBox);
			break;
		}
	}
	
	public static void addToPanel (JPanel thePanel, InertGuiHead newHead) {
		switch (newHead.ckBoxPos) {
		case CKBOX_OFF:
			thePanel.add(newHead.label1);
			thePanel.add(newHead.label2);
			thePanel.add(newHead.label3);
			break;
		case CKBOX_LEFT:
			thePanel.add(newHead.ckBox);
			thePanel.add(newHead.label2);
			thePanel.add(newHead.label3);
			break;
		case CKBOX_MID:
			thePanel.add(newHead.label1);
			thePanel.add(newHead.ckBox);
			thePanel.add(newHead.label3);
			break;
		case CKBOX_RIGHT:
			thePanel.add(newHead.label1);
			thePanel.add(newHead.label2);
			thePanel.add(newHead.ckBox);
			break;
		}
	}
}
