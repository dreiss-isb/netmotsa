package djr.motif.sampler;
import java.util.*;
import cern.jet.random.Poisson;
import cern.jet.random.Gamma;
import djr.util.*;
import djr.util.array.*;

/**
 * Class <code>MultiSampler</code>.
 *
 * @author <a href="mailto:dreiss@systemsbiology.org">David Reiss</a>
 * @version 1.9978 (Fri Nov 07 05:56:26 PST 2003)
 */
public class MultiSampler extends SiteSampler {
   int nmots[], nsitestot[], priorParam, overlap;
   double priorPDF[];
   String priorFunc;

   // Temporary arrays kept global for speed
   double psite[], pm[];

   public MultiSampler() { };
   public MultiSampler( String argv[] ) { Initialize( argv ); }
   public MultiSampler( String args ) { this( MyUtils.Tokenize( args, " " ) ); }

   protected boolean Initialize( String argv[] ) {
      initialized = super.Initialize( argv );
      if ( ! initialized ) return false;
      initialized = false;

      // Number of times there are a given number of sites over all sequences
      nsitestot = IntUtils.New( maxPerSeq + 1 );

      // Number of each motif in each sequence
      nmots = IntUtils.New( NS );

      // Probs of a given site in a sequence belonging to each motif
      // Prior prob of there being this many sites belonging to a motif in the sequence
      psite = DoubleUtils.New( maxPerSeq + 1 );
      pm = DoubleUtils.New( maxLen ); //Matrix( nMotifs + 1, maxLen );

      if ( "poisson".equals( priorFunc ) ) {
	 // Seed the Poisson prior distribution for # of motif sites per sequence
	 Poisson poi = new Poisson( priorParam, IntUtils.rand );
	 priorPDF = DoubleUtils.New( maxPerSeq + 1 );
	 double sum = 0;
	 for ( int i = minPerSeq; i <= maxPerSeq; i ++ ) {
	    priorPDF[ i ] = poi.pdf( i ); sum += priorPDF[ i ]; }
	 DoubleUtils.Divide( priorPDF, sum );
      } else if ( "gamma".equals( priorFunc ) ) {
	 // Seed w/ a gamma function. In this case a good priorParam is 2 or 3
	 Gamma gam = new Gamma( priorParam, 1.0, IntUtils.rand );
	 priorPDF = DoubleUtils.New( maxPerSeq + 1 );
	 double sum = 0;
	 for ( int i = minPerSeq; i <= maxPerSeq; i ++ ) {
	    priorPDF[ i ] = gam.pdf( i ); sum += priorPDF[ i ]; }
	 DoubleUtils.Divide( priorPDF, sum );
      }

      initialized = true;
      return true;
   }

   protected void ShuffleSites() {
      IntUtils.Zero( nmots );
      IntUtils.Zero( nsitestot );
      GetSites().ShuffleSites();
   }

   protected Map InitializeState( boolean self ) {
      Map out = super.InitializeState( self );
      if ( self ) {
	 out.put( "nmots", nmots );
	 out.put( "nsitestot", nsitestot );
      } else {
	 out.put( "nmots", IntUtils.New( nmots ) );
	 out.put( "nsitestot", IntUtils.New( nsitestot ) );
      }
      return out;
   }

   protected void CopyState( Map to, Map from ) {
      super.CopyState( to, from );
      IntUtils.Copy( (int[]) to.get( "nmots" ), (int[]) from.get( "nmots" ) );
      IntUtils.Copy( (int[]) to.get( "nsitestot" ), (int[]) from.get( "nsitestot" ) );
   }

   protected void IterateSampler( int niter ) {
      int itermax = niter == -1 ? NS : niter;
      for ( int i = 0; i < itermax; i ++ ) {
	 int ii = niter == -1 ? i : IntUtils.RandChoose( 0, NS-1 );
	 AddRemoveFromCounts( false, ii );
	 GetSites().RemoveAllSites( ii );
	 Sample( ii );
	 AddRemoveFromCounts( true, ii );
      }
   }

   protected void Sample( int ii ) {
      int llen = len[ ii ], maxl = llen - W;
      // Compute probs that each site in the sequence was generated by each motif (relative to 
      // background)
      ComputeProbsForSequence( ii, 0, qx, false );

      // Prior for # of sites per sequence (ns) given by Poisson distribution
      double nstot = IntUtils.Sum( nsitestot );
      for ( int i = minPerSeq; i <= maxPerSeq; i ++ ) 
	 psite[ i ] = ( nsitestot[ i ] + nstot ) * priorPDF[ i ];
      DoubleUtils.MaxNorm( psite );
      int maxS = DoubleUtils.Sample( psite );

      double dmaxl = (double) maxl;
      DoubleUtils.Copy( pm, qx );

      int count = 0, tries = 0, count2 = 0, site, mmot;
      double sums[] = DoubleUtils.New( nMotifs + 1 );

      while( tries ++ < 1000 && count2 ++ < maxS && dmaxl > W * 2 ) {
	 sums[ 0 ] = dmaxl;
	 DoubleUtils.MaxNorm( pm );
	 if ( sums[ 1 ] <= 0.0 ) sums[ 1 ] = DoubleUtils.Sum( qx );
	 DoubleUtils.MaxNorm( sums );
	 for ( int mot = 0; mot <= nMotifs; mot ++ ) 
	    sums[ mot ] /= ( 1.0 - sums[ mot ] + 0.001 );
	 DoubleUtils.MaxNorm( sums );
	 mmot = -1;
	 if ( noSamp ) mmot = DoubleUtils.WhereMax( sums ) - 1;
	 else {
	    int tries2 = 0;
	    while( mmot < 0 && tries2 ++ < 100 ) mmot = DoubleUtils.Sample( sums ) - 1;
	 }
	 if ( mmot < 0 ) continue;//break; //continue;
	 site = -1;
	 if ( noSamp ) site = DoubleUtils.WhereMax( pm, llen - W + 1 );
	 else {
	    int tries2 = 0;
	    while( site == -1 && tries2 ++ < 100 ) 
	       site = DoubleUtils.Sample( pm, llen - W + 1 );
	 }
	 if ( site < 0 ) continue;//break;
	 if ( count > minPerSeq && ! IsSiteValid( site, ii ) ) continue;
	 int top = site + W - overlap;
	 for ( int i = site - W + 1 + overlap; i < top; i ++ ) {
	    if ( i < 0 ) { i = -1; continue; } if ( i > maxl ) break;
	    sums[ 1 ] -= qx[ i ];
	    pm[ i ] = qx[ i ] = 0.0;
	    dmaxl --;
	 }
	 GetSites().AddSite( ii, site, 0 );
	 count ++;
      }
   }

   protected void AddRemoveFromCounts( boolean add, int ii ) {
      super.AddRemoveFromCounts( add, ii, -1 );
      nmots[ ii ] = 0;
      if ( add ) {
	 int ns = GetSites().GetNSites( ii );
	 for ( int j = 0; j < ns; j ++ ) nmots[ ii ] ++;
	 nsitestot[ ns ] ++;
      }
   }

   protected void FillTheArrays() {
      IntUtils.Zero( nsitestot );
      super.FillTheArrays();
   }

   public void SetupArgs( ArgProcessor argProc ) {
      super.SetupArgs( argProc );      
      argProc.ModifyDefaultArg( "max", "20" );

      argProc.AddArg( "MultiSampler Parameters" );
      argProc.AddArg( "o", "<irange:0:30>", "0", "amount of overlap allowed" );
      argProc.AddArg( "prior", "<poisson|gamma>", "poisson", "prior distribution for number motif sites per sequence" );
      argProc.AddArg( "M", "<irange:0:100>", "1", "expected mean number of motif sites per sequence" );
   }

   public void SetArgs( ArgProcessor proc ) {
      super.SetArgs( proc );
      overlap = proc.getIntArg( "o" );
      priorFunc = proc.getArg( "prior" );
      priorParam = proc.getIntArg( "M" );
   }

   public static void main( String argv[] ) {
      MultiSampler samp = new MultiSampler( argv );
      if ( ! samp.IsInitialized() ) return;
      samp.Run();
   }
}
