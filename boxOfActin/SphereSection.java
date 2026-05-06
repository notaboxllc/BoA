package boxOfActin;
//
//  CLASS
//	  SphereSection.. modified from
//    Arch	-  generalized arch
//
//  DESCRIPTION
//    This class builds a generalized arch where incoming parameters
//    specify the angle range in theta (around the equator of a sphere),
//    the angle range in phi (north-south), the number of subdivisions
//    in theta and phi, and optionally radii and outer-to-inner wall
//    thickness variations as phi varies from its starting value to
//    its ending value.  If the thicknesses are 0.0, then only an outer
//    surface is created.
//
//    Using this class, you can create spheres with or without inner
//    surfaces, hemisphers, quarter spheres, and arches stretched or
//    compressed vertically.
//
//    This is probably not as general as it could be, but it was enough
//    for the purposes at hand.
//
//  SEE ALSO
//    ModernFire
//
//  AUTHOR
//    David R. Nadeau / San Diego Supercomputer Center
//
//

/*  <ParMSpindle - a simulation of ParM based plasmid segregation>
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

import javax.media.j3d.*;
import javax.vecmath.*;

public class SphereSection extends Shape3D {

	public SphereSection( double startPhi, double endPhi, int nPhi,
		double startTheta, double endTheta, int nTheta, double radius, Appearance app )
	{
		
		double theta,phi;
		double[] xyz  = new double[3];
		float[]  norm = new float[3];

		// Compute some values for our looping
		double deltaTheta = (endTheta - startTheta) / (double)(nTheta-1);
		double deltaPhi   = (endPhi - startPhi) / (double)(nPhi-1);
		
		//  Create geometry
		int vertexCount = nTheta * nPhi;
		int indexCount = (nTheta-1) * (nPhi-1) * 4;  // Outer surface
		
		IndexedQuadArray polys = new IndexedQuadArray(
			vertexCount,
			GeometryArray.COORDINATES |
			GeometryArray.NORMALS,
			indexCount );


		//
		//  Compute coordinates, normals, and texture coordinates
		//
		theta = startTheta;
		int index = 0;
		for ( int i = 0; i < nTheta; i++ )
		{
			phi = startPhi;

			for ( int j = 0; j < nPhi; j++ )
			{
				norm[0] = (float)(Math.cos( phi ) * Math.cos( theta ));
				norm[1] = (float)(Math.sin( phi ));
				norm[2] = (float)(-Math.cos( phi ) * Math.sin( theta ));
				xyz[0] = radius * norm[0];
				xyz[1] = radius * norm[1];
				xyz[2] = radius * norm[2];
				polys.setCoordinate( index, xyz );
				for (int k=0;k<norm.length;k++) { norm[k] *= -1.0f; }
				polys.setNormal( index, norm );
				index++;

				phi += deltaPhi;
			}
			theta += deltaTheta;
		}


		//
		//  Compute coordinate indexes
		//  (also used as normal and texture indexes)
		//
		index = 0;
		int phiRow = nPhi;
		int phiCol = 1;
		int[] indices = new int[indexCount];

		// Outer surface
		int n;
		for ( int i = 0; i < nTheta-1; i++ )
		{
			for ( int j = 0; j < nPhi-1; j++ )
			{
				n = i*phiRow + j*phiCol;
				indices[index+0] = n;
				indices[index+1] = n+phiRow;
				indices[index+2] = n+phiRow+phiCol;
				indices[index+3] = n+phiCol;
				index += 4;
			}
		}


		polys.setCoordinateIndices( 0, indices );
		polys.setNormalIndices( 0, indices );
	//	polys.setTextureCoordinateIndices( 0, indices );


		//
		//  Build a shape
		//
		this.setCapability(Shape3D.ALLOW_APPEARANCE_OVERRIDE_WRITE);
		this.setCapability( Shape3D.ALLOW_APPEARANCE_WRITE );
		this.setGeometry( polys );
		this.setAppearance( app );
	}


}

