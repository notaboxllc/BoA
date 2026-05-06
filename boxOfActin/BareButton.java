package boxOfActin;
/*
 * BareButton... an extension of JButton that has a particular appearance, behavior, etc
 */

import javax.swing.*;

public class BareButton extends JButton {
	
	public BareButton (String name) {
		super (name);
		this.setBackground(Env.controlForeColor);
		this.setForeground(Env.controlBackColor);
		//this.setBorder(BorderFactory.createEmptyBorder());
	}
	
}
