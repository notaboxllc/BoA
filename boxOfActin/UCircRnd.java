package boxOfActin;
/* UCircRnd.java  ..... an object to store v1,v2,rsq, and facterm where:
	v1 and v2 are a pair of uniformly generated random numbers such that...
	rsq = v1^2 + v2^2 <= 1  (in a unit circle)
	facterm = -4*deltaT*Math.log(rsq)/rsq

	Part of a method for generating gaussian distributions of random number taken
	from 'Recipes in C'
*/

import java.lang.Math;
import ec.util.MersenneTwisterFast;

public class UCircRnd {
	double v1,v2,rsq,facterm;

	public UCircRnd (double deltaT) {
		newValue(deltaT);
	}

	public void newValue (double deltaT) {
		rsq = 0;
		while ((rsq >= 1.0) | (rsq == 0)) {	// while length of vector isn't in the unit circle do..
			v1 = 2*Env.mtRNG.nextDouble()-1;		// one uniform number in the square from -1 to 1...
			v2 = 2*Env.mtRNG.nextDouble()-1;		// ... and another one.
			rsq = v1*v1 + v2*v2;			// length of the vector.
		}
		facterm = -4*deltaT*Math.log(rsq)/rsq;
	}

	// Per-worker RNG variant — the per-Thing PRNG was retired in the per-worker
	// RNG consolidation. Caller draws from WorkerScratch.rng resolved once per
	// dispatch (calcRandomForces hot loop) rather than per-Thing PRNG state.
	public void newValue (double deltaT, MersenneTwisterFast prng) {
		rsq = 0;
		while ((rsq >= 1.0) | (rsq == 0)) {	// while length of vector isn't in the unit circle do..
			v1 = 2*prng.nextDouble()-1;		// one uniform number in the square from -1 to 1...
			v2 = 2*prng.nextDouble()-1;		// ... and another one.
			rsq = v1*v1 + v2*v2;			// length of the vector.
		}
		facterm = -4*deltaT*Math.log(rsq)/rsq;
	}
	
	/*public void newValue (double deltaT) {
		rsq = 0;
		while ((rsq >= 1.0) | (rsq == 0)) {	// while length of vector isn't in the unit circle do..
			v1 = 2*Math.random()-1;		// one uniform number in the square from -1 to 1...
			v2 = 2*Math.random()-1;		// ... and another one.
			rsq = v1*v1 + v2*v2;			// length of the vector.
		}
		facterm = -4*deltaT*Math.log(rsq)/rsq;
	}*/
}
			
