ParMSpindle:

This Java code simulates the segregation, to opposite sides of a bacterium prior to cell division, of gene carrying plasmids by the bacterial actin homolog ParM.  In the simulation, plasmids are represented by spheres, which might be seen as the effective capture radius of the plasmid. These plasmids have the ability to bind and nucleate (depending upon parameter settings in a particular run) ParM filaments via an unrepresented ParR-ParC complex.  The ParM filaments are comprised of a set of filament segments; each adjoining filament segment is connected to its neighbor by a rotational spring and translational forces calculated by a pairwise Lagrange multiplier approximation.

The main class is ParMSpindle, so the source code can be compiled on a machine with Java and Java3D environments installed...

javac ParMSpindle.java 

It can likewise be run with 

java ParMSpindle

You're going to need a large memory allocation to do any real simulation.  This can be set at runtime with

java -Xmx800M ParMSpindle 

where the 800M means 800 MB max heap size (you may, and may need to, use larger or smaller allocations, depending on the run parameters).

There are several runtime options that can be invoked... type
java ParMSpindle -help 
to see a list.  

The code allows you to set many of the important parameters at runtime through a GUI.  These values may be saved in a parameter file, under the File menu.  You can automatically load this parameter file using the -pf option..

java -Xmx800M ParMSpindle -pf myParamFile

The code can be modified in any text editor, but we recommend an IDE such as Eclipse (free and available for OSX, Windows, Linux, and others).

Any questions, please email me:  jalberts@u.washington.edu