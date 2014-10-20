package uff;

import vec.*;
import edu.mines.jtk.dsp.*;
import edu.mines.jtk.util.*;
import static edu.mines.jtk.util.ArrayMath.*;

/**
 * Estimates shift vectors to flatten features in 2D and 3D images.
 * In 2D, the shift vector field has two components r1(x1,x3) and r3(x1,x3).
 * In 3D, the vector field has three components r1(x1,x2,x3), r2(x1,x2,x3),
 * and r3(x1,x2,x3).
 * @author Xinming Wu, Simon Luo and Dave Hale
 * @version 2014.02.01
 */
public class FlattenerRTC {

  /**
   * Constructs a flattener.
   * @param sigma1 smoother half-width for 1st dimension.
   * @param sigma2 smoother half-width for 2nd dimension.
   */
  public FlattenerRTC(double sigma1, double sigma2) {
    _sigma1 = (float)sigma1;
    _sigma2 = (float)sigma2;
  }

  /**
   * Estimates shift vectors for a 2D image.
   * @param p array of parameters {u1,u2,el,a}.
   * @return array of shifts {r1,r2}.
   */
  public float[][][] findShifts(float[][][] p) {
    int n2 = p[0].length;
    int n1 = p[0][0].length;
    float[][][] p0 = copy(p);
    float[][][] b = new float[2][n2][n1];
    float[][][] r = new float[2][n2][n1];
    float[][][] rc = new float[2][n2][n1];
    VecArrayFloat3 vr = new VecArrayFloat3(r);
    VecArrayFloat3 vb = new VecArrayFloat3(b);
    Smoother2 s2 = new Smoother2(_sigma1,_sigma2,p[2]);
    CgSolver cg = new CgSolver(_small,_inner);
    A2 ma = new A2(_epsilon,s2,p);
    for (int outer=0; outer<_outer; ++outer) {
      if (outer>0) {
        copy(r,rc);
        s2.apply(rc);
        updateParameters(rc,p0,p);
        vb.zero();
      }
      makeRhs(p,b);
      s2.applyTranspose(b);
      int inner = cg.solve(ma,vb,vr).niter;
      if (inner==0) break;
    }
    s2.apply(r);
    return r;
  }

  public float[][][][] computeShifts(
    int[][][] fm, float[][][] cx, float[][][][] p, float[][][] cp) 
  {
    int n3 = p[0].length;
    int n2 = p[0][0].length;
    int n1 = p[0][0][0].length;
    _cm = new float[5][n3][n2][n1];
    _cm[0] = fillfloat(-1.0f,n1,n2,n3);
    setControlMap(cx);
    float[][][][] p0 =  scopy(p);
    float[][][][] r  = new float[3][n3][n2][n1];
    float[][][][] b  = new float[3][n3][n2][n1];
    int ns = cx[0].length;
    int[] cn = new int[ns];
    float[][][] cu = copy(cx);
    countPoints(cu,cn);
    initializeShifts(cn,cu,cx,r);
    VecArrayFloat4 vr = new VecArrayFloat4(r);
    VecArrayFloat4 vb = new VecArrayFloat4(b);
    Smoother3 s3 = new Smoother3(_sigma1,_sigma2,_sigma2,p0[3]);
    M3 m3 = new M3(cn,cu,s3);
    A3 a3 = new A3(_epsilon,p);
    for (int iter=0; iter<_outer; ++iter) {
      _inner =5+5*iter;
      CgSolver cg = new CgSolver(_small,_inner);
      if(iter>0){
        updateParameters(r,p0,p);
        updateConstraints(p0[3],cx,r,cu);
        countPoints(cu,cn);
        //cleanConstraints(cn,p0[3],cu);
        //countPoints(cu,cn);
        vr.zero();
        initializeShifts(cn,cu,cx,r);
      }
      vb.zero();
      makeRhs(p,b);
      cg.solve(a3,m3,vb,vr);
    }
    cleanShifts(fm,r);
    cleanShifts(fm,r);
    cleanShifts(fm,r);
    addPoints(cn,cu,cp);
    return r;
  }

  private void addPoints(int[] cn, float[][][] cu, float[][][] cp) {
    int ns = cu[0].length;
    for (int is=0; is<ns; ++is) {
      if (cn[is]>1) {
        int np = cu[0][is].length-1;
        for (int ip=0; ip<np; ++ip) {
          int u1 = round(cu[0][is][ip]);
          int u2 = round(cu[1][is][ip]);
          int u3 = round(cu[2][is][ip]);
          cp[u3][u2][u1] = 1.0f;
        }
      }
    }
  }

  /**
   * Estimates shift vectors for a 3D image.
   * @param p array of parameters {u1,u2,u3,ep,a}.
   * @return array of shifts {r1,r2,r3}.
   */
  /*
  public float[][][][] findShifts(float[][][][] p) {
    int n3 = p[0].length;
    int n2 = p[0][0].length;
    int n1 = p[0][0][0].length;
    float[][][][] p0 = scopy(p);
    float[][][][] b  = new float[3][n3][n2][n1];
    float[][][][] r  = new float[3][n3][n2][n1];
    float[][][][] rc = new float[3][n3][n2][n1];
    VecArrayFloat4 vr = new VecArrayFloat4(r);
    VecArrayFloat4 vb = new VecArrayFloat4(b);
    Smoother3 s3 = new Smoother3(_sigma1,_sigma2,_sigma2,p[3]);
    CgSolver cg = new CgSolver(_small,_inner);
    A3 ma = new A3(_epsilon,s3,p);
    for (int outer=0; outer<_outer; ++outer) {
      if (outer>0) {
        scopy(r,rc);
        s3.apply(rc);
        updateParameters(rc,p0,p);
        vb.zero();
      }
      makeRhs(p,b);
      s3.applyTranspose(b);
      int inner = cg.solve(ma,vb,vr).niter;
      if (inner==0) break;
    }
    s3.apply(r);
    return r;
  }
  */

  /**
   * Applies shifts using sinc interpolation.
   * The returned array is a sampling of g(u) = f(u-r(u)).
   * @param r input array {r1,r2} of shifts.
   * @param f input image.
   * @param g output shifted image.
   */
  public void applyShifts(
    float[][][] r, float[][] f, float[][] g)
  {
    int n1 = f[0].length;
    int n2 = f.length;
    float[][] r1 = r[0], r2 = r[1];
    SincInterpolator si = new SincInterpolator();
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        g[i2][i1] = si.interpolate(
          n1,1.0,0.0,n2,1.0,0.0,f,i1-r1[i2][i1],i2-r2[i2][i1]);
      }
    }
  }

  /**
   * Applies shifts using sinc interpolation.
   * @param r input array {r1,r2,r3} of shifts.
   * @param f input image.
   * @param g output shifted image.
   */
  public void applyShifts(
    float[][][][] r, float[][][] f, float[][][] g)
  {
    final int n1 = f[0][0].length;
    final int n2 = f[0].length;
    final int n3 = f.length;
    final float[][][] ff = f;
    final float[][][] gf = g;
    final float[][][] r1 = r[0], r2 = r[1], r3 = r[2];
    final SincInterpolator si = new SincInterpolator();
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2)
        for (int i1=0; i1<n1; ++i1)
          gf[i3][i2][i1] = si.interpolate(
            n1,1.0,0.0,n2,1.0,0.0,n3,1.0,0.0,
            ff,i1-r1[i3][i2][i1],i2-r2[i3][i2][i1],i3-r3[i3][i2][i1]);
    }});
  }

  ///////////////////////////////////////////////////////////////////////////
  // private

  private static final float W0 = 1.000f; // flatness
  private static final float W1 = 0.011f; // distance
  private static final float W2 = 0.001f; // thickness
  //private static final float W1 = 0.0f; // distance
  //private static final float W2 = 0.0f; // thickness

  private float _sigma1 = 6.0f; // half-width of smoother in 1st dimension
  private float _sigma2 = 6.0f; // half-width of smoother in 2nd dimension
  private float _epsilon = 0.000f; // damping for stability?
  private float _small = 0.010f; // stop CG iterations if residuals are small
  //private float _small = 0.001f; // stop CG iterations if residuals are small
  private int _inner = 10; // maximum number of inner CG iterations
  private int _outer = 20; // maximum number of outer iterations

  // for constraints
  private float[][][][] _cm;

  private static class A2 implements CgSolver.A {
    A2(float epsilon, Smoother2 smoother, float[][][] p) {
      _epsilon = epsilon;
      _smoother = smoother;
      _p = p;
    }
    public void apply(Vec vx, Vec vy) {
      VecArrayFloat3 v3x = (VecArrayFloat3)vx;
      VecArrayFloat3 v3y = (VecArrayFloat3)vy;
      VecArrayFloat3 v3z = v3x.clone();
      v3y.zero();
      float[][][] x = v3z.getArray();
      float[][][] y = v3y.getArray();
      _smoother.apply(x);
      applyLhs(_p,x,y);
      _smoother.applyTranspose(y);
      if (_epsilon>0.0f)
        v3y.add(1.0,v3x,_epsilon*_epsilon);
    }
    private float _epsilon = 0.0f;
    private Smoother2 _smoother;
    private float[][][] _p;
  }
  private static class A3 implements CgSolver.A {
    A3(float epsilon, float[][][][] p) {
      _epsilon = epsilon;
      _p = p;
    }
    public void apply(Vec vx, Vec vy) {
      VecArrayFloat4 v4x = (VecArrayFloat4)vx;
      VecArrayFloat4 v4y = (VecArrayFloat4)vy;
      float[][][][] x = v4x.getArray();
      float[][][][] y = v4y.getArray();
      float[][][][] z = scopy(x);
      v4y.zero();
      applyLhs(_p,z,y);
      if (_epsilon>0.0f)
        v4y.add(1.0,v4x,_epsilon*_epsilon);
    }
    private float[][][][] _p;
    private float _epsilon = 0.0f;
  }

  // Preconditioner; includes smoothers and (optional) constraints.
  private static class M3 implements CgSolver.A {
    M3(int[] cn, float[][][] cu, Smoother3 s3) {
      _cn = cn;
      _cu = cu;
      _s3 = s3;
    }
    public void apply(Vec vx, Vec vy) {
      VecArrayFloat4 v4x = (VecArrayFloat4)vx;
      VecArrayFloat4 v4y = (VecArrayFloat4)vy;
      float[][][][] x = v4x.getArray();
      float[][][][] y = v4y.getArray();
      scopy(x,y);
      constrain(_cn,_cu,y[0]);
      //constrain(_cn,_cu,y[1]);
      //constrain(_cn,_cu,y[2]);
      _s3.apply(y);
      constrain(_cn,_cu,y[0]);
      //constrain(_cn,_cu,y[1]);
      //constrain(_cn,_cu,y[2]);
    }
    private Smoother3 _s3;
    private int[] _cn = null;
    private float[][][] _cu=null;
  }


  // 2D RHS
  private static void makeRhs(float[][][] p, float[][][] y) {
    int n1 = y[0][0].length;
    int n2 = y[0].length;
    float[][] y1 = y[0]; float[][] y2 = y[1];
    float[][] u1 = p[0]; float[][] u2 = p[1]; float[][] el = p[2];
    for (int i2=1; i2<n2; ++i2) {
      for (int i1=1; i1<n1; ++i1) {
        float u1i = u1[i2][i1];
        float u2i = u2[i2][i1];
        float eli = el[i2][i1]*0.5f;
        float u1m = 1.0f-u1i;
        float b1 = W2*eli*u1m;
        float b2 = W2*eli*u2i;
        float b3 = W0*eli*u2i;
        float b4 = W1*eli*u1m;
        float y11 = b1*u1i+b2*u2i;
        float y12 = b3*u1i+b4*u2i;
        float y21 = b1*u2i-b2*u1i;
        float y22 = b3*u2i-b4*u1i;
        float y1a = y11+y12;
        float y1b = y11-y12;
        float y2a = y21+y22;
        float y2b = y21-y22;
        y1[i2  ][i1  ] += y1a;
        y1[i2  ][i1-1] -= y1b;
        y1[i2-1][i1  ] += y1b;
        y1[i2-1][i1-1] -= y1a;
        y2[i2  ][i1  ] += y2a;
        y2[i2  ][i1-1] -= y2b;
        y2[i2-1][i1  ] += y2b;
        y2[i2-1][i1-1] -= y2a;
      }
    }
  }

  // 2D LHS
  private static void applyLhs(
    float[][][] p, float[][][] x, float[][][] y)
  {
    int n1 = y[0][0].length;
    int n2 = y[0].length;
    float[][] x1 = x[0]; float[][] x2 = x[1];
    float[][] y1 = y[0]; float[][] y2 = y[1];
    float[][] u1 = p[0]; float[][] u2 = p[1]; float[][] el = p[2];
    for (int i2=1; i2<n2; ++i2) {
      for (int i1=1; i1<n1; ++i1) {
        float x100 = x1[i2  ][i1  ];
        float x101 = x1[i2  ][i1-1];
        float x110 = x1[i2-1][i1  ];
        float x111 = x1[i2-1][i1-1];
        float x200 = x2[i2  ][i1  ];
        float x201 = x2[i2  ][i1-1];
        float x210 = x2[i2-1][i1  ];
        float x211 = x2[i2-1][i1-1];
        float u1i = u1[i2][i1];
        float u2i = u2[i2][i1];
        float eli = el[i2][i1]*0.25f;
        float x1a = x100-x111;
        float x1b = x101-x110;
        float x2a = x200-x211;
        float x2b = x201-x210;
        float x11 = x1a-x1b;
        float x12 = x1a+x1b;
        float x21 = x2a-x2b;
        float x22 = x2a+x2b;

        float b1 = W2*eli*( x11*u1i+x21*u2i);
        float b2 = W2*eli*(-x21*u1i+x11*u2i);
        float b3 = W0*eli*( x12*u1i+x22*u2i);
        float b4 = W1*eli*(-x22*u1i+x12*u2i);

        float y11 = b1*u1i+b2*u2i;
        float y12 = b3*u1i+b4*u2i;
        float y21 = b1*u2i-b2*u1i;
        float y22 = b3*u2i-b4*u1i;
        float y1a = y11+y12;
        float y1b = y11-y12;
        float y2a = y21+y22;
        float y2b = y21-y22;
        y1[i2  ][i1  ] += y1a;
        y1[i2  ][i1-1] -= y1b;
        y1[i2-1][i1  ] += y1b;
        y1[i2-1][i1-1] -= y1a;
        y2[i2  ][i1  ] += y2a;
        y2[i2  ][i1-1] -= y2b;
        y2[i2-1][i1  ] += y2b;
        y2[i2-1][i1-1] -= y2a;
      }
    }
  }

  private static void makeRhs(
    final float[][][][] p, final float[][][][] y)
  { 
    final int n3 = y[0].length;
    // i3 = 1,3,5,...
    Parallel.loop(1,n3,2,new Parallel.LoopInt() {
    public void compute(int i3) {
      makeRhsSlice3(i3,p,y);
    }});
    // i3 = 2,4,6,...
    Parallel.loop(2,n3,2,new Parallel.LoopInt() {
    public void compute(int i3) {
      makeRhsSlice3(i3,p,y);
    }});
  }

  private static void makeRhsSlice3(
    int i3, float[][][][] p, float[][][][] y)
  { 
    int n1 = y[0][0][0].length;
    int n2 = y[0][0].length;
    float[][][] y1 = y[0]; float[][][] y2 = y[1]; float[][][] y3 = y[2];
    float[][][] u1 = p[0]; float[][][] u2 = p[1]; float[][][] u3 = p[2];
    float[][][] ep = p[3];
    for (int i2=1; i2<n2; ++i2) {
      float[] y100 = y1[i3  ][i2  ];
      float[] y101 = y1[i3  ][i2-1];
      float[] y110 = y1[i3-1][i2  ];
      float[] y111 = y1[i3-1][i2-1];
      float[] y200 = y2[i3  ][i2  ];
      float[] y201 = y2[i3  ][i2-1];
      float[] y210 = y2[i3-1][i2  ];
      float[] y211 = y2[i3-1][i2-1];
      float[] y300 = y3[i3  ][i2  ];
      float[] y301 = y3[i3  ][i2-1];
      float[] y310 = y3[i3-1][i2  ];
      float[] y311 = y3[i3-1][i2-1];
      for (int i1=1,i1m=0; i1<n1; ++i1,++i1m) {
        float u1i = u1[i3][i2][i1];
        float u2i = u2[i3][i2][i1];
        float u3i = u3[i3][i2][i1];
        float epi = ep[i3][i2][i1]*0.25f;
        float u1p = u1i+1.0f;
        float u1u1p = u1i*u1p;
        float u2u1p = u2i*u1p;
        float u3u1p = u3i*u1p;
        float u2u3 = u2i*u3i;
        float u2smu1p = u2i*u2i-u1p;
        float u3smu1p = u3i*u3i-u1p;

        float b1 = W2*epi*(u1i*u1i-1.0f);
        float b2 = W2*epi*(u2u1p);
        float b3 = W2*epi*(u3u1p);
        float b4 = W0*epi*(u2u1p);
        float b5 = W1*epi*(u2i*u2i);
        float b6 = W1*epi*(u2u3);
        float b7 = W0*epi*(u3u1p);
        float b8 = W1*epi*(u2u3);
        float b9 = W1*epi*(u3i*u3i);

        float y11 = b1*u1u1p + b2*u2u1p   + b3*u3u1p;
        float y12 = b4*u1u1p + b5*u2u1p   + b6*u3u1p;
        float y13 = b7*u1u1p + b8*u2u1p   + b9*u3u1p;
        float y21 = b1*u2u1p + b2*u2smu1p + b3*u2u3;
        float y22 = b4*u2u1p + b5*u2smu1p + b6*u2u3;
        float y23 = b7*u2u1p + b8*u2smu1p + b9*u2u3;
        float y31 = b1*u3u1p + b2*u2u3    + b3*u3smu1p;
        float y32 = b4*u3u1p + b5*u2u3    + b6*u3smu1p;
        float y33 = b7*u3u1p + b8*u2u3    + b9*u3smu1p;

        float y1a = y11+y12+y13;
        float y1b = y11-y12+y13;
        float y1c = y11+y12-y13;
        float y1d = y11-y12-y13;
        float y2a = y21+y22+y23;
        float y2b = y21-y22+y23;
        float y2c = y21+y22-y23;
        float y2d = y21-y22-y23;
        float y3a = y31+y32+y33;
        float y3b = y31-y32+y33;
        float y3c = y31+y32-y33;
        float y3d = y31-y32-y33;
        y100[i1 ] += y1a;
        y100[i1m] -= y1d;
        y101[i1 ] += y1b;
        y101[i1m] -= y1c;
        y110[i1 ] += y1c;
        y110[i1m] -= y1b;
        y111[i1 ] += y1d;
        y111[i1m] -= y1a;
        y200[i1 ] += y2a;
        y200[i1m] -= y2d;
        y201[i1 ] += y2b;
        y201[i1m] -= y2c;
        y210[i1 ] += y2c;
        y210[i1m] -= y2b;
        y211[i1 ] += y2d;
        y211[i1m] -= y2a;
        y300[i1 ] += y3a;
        y300[i1m] -= y3d;
        y301[i1 ] += y3b;
        y301[i1m] -= y3c;
        y310[i1 ] += y3c;
        y310[i1m] -= y3b;
        y311[i1 ] += y3d;
        y311[i1m] -= y3a;
      }
    }
  }

  private static void applyLhs(
    final float[][][][] p, final float[][][][] x, final float[][][][] y)
  { 
    final int n3 = y[0].length;
    Parallel.loop(1,n3,2,new Parallel.LoopInt() {
    public void compute(int i3) {
      applyLhsSlice3(i3,p,x,y);
    }});
    Parallel.loop(2,n3,2,new Parallel.LoopInt() {
    public void compute(int i3) {
      applyLhsSlice3(i3,p,x,y);
    }});
  }

  // 3D LHS
  private static void applyLhsSlice3(
    int i3, float[][][][] p, float[][][][] x, float[][][][] y)
  {
    int n1 = y[0][0][0].length;
    int n2 = y[0][0].length;
    float[][][] x1 = x[0]; float[][][] x2 = x[1]; float[][][] x3 = x[2];
    float[][][] y1 = y[0]; float[][][] y2 = y[1]; float[][][] y3 = y[2];
    float[][][] u1 = p[0]; float[][][] u2 = p[1]; float[][][] u3 = p[2];
    float[][][] ep = p[3];
    for (int i2=1; i2<n2; ++i2) {
      float[] x100 = x1[i3  ][i2  ];
      float[] x101 = x1[i3  ][i2-1];
      float[] x110 = x1[i3-1][i2  ];
      float[] x111 = x1[i3-1][i2-1];
      float[] x200 = x2[i3  ][i2  ];
      float[] x201 = x2[i3  ][i2-1];
      float[] x210 = x2[i3-1][i2  ];
      float[] x211 = x2[i3-1][i2-1];
      float[] x300 = x3[i3  ][i2  ];
      float[] x301 = x3[i3  ][i2-1];
      float[] x310 = x3[i3-1][i2  ];
      float[] x311 = x3[i3-1][i2-1];
      float[] y100 = y1[i3  ][i2  ];
      float[] y101 = y1[i3  ][i2-1];
      float[] y110 = y1[i3-1][i2  ];
      float[] y111 = y1[i3-1][i2-1];
      float[] y200 = y2[i3  ][i2  ];
      float[] y201 = y2[i3  ][i2-1];
      float[] y210 = y2[i3-1][i2  ];
      float[] y211 = y2[i3-1][i2-1];
      float[] y300 = y3[i3  ][i2  ];
      float[] y301 = y3[i3  ][i2-1];
      float[] y310 = y3[i3-1][i2  ];
      float[] y311 = y3[i3-1][i2-1];
      for (int i1=1,i1m=0; i1<n1; ++i1,++i1m) {
        float x1000 = x100[i1 ];
        float x1001 = x100[i1m];
        float x1010 = x101[i1 ];
        float x1011 = x101[i1m];
        float x1100 = x110[i1 ];
        float x1101 = x110[i1m];
        float x1110 = x111[i1 ];
        float x1111 = x111[i1m];
        float x2000 = x200[i1 ];
        float x2001 = x200[i1m];
        float x2010 = x201[i1 ];
        float x2011 = x201[i1m];
        float x2100 = x210[i1 ];
        float x2101 = x210[i1m];
        float x2110 = x211[i1 ];
        float x2111 = x211[i1m];
        float x3000 = x300[i1 ];
        float x3001 = x300[i1m];
        float x3010 = x301[i1 ];
        float x3011 = x301[i1m];
        float x3100 = x310[i1 ];
        float x3101 = x310[i1m];
        float x3110 = x311[i1 ];
        float x3111 = x311[i1m];
        float x1a = x1000-x1111;
        float x1b = x1001-x1110;
        float x1c = x1010-x1101;
        float x1d = x1100-x1011;
        float x2a = x2000-x2111;
        float x2b = x2001-x2110;
        float x2c = x2010-x2101;
        float x2d = x2100-x2011;
        float x3a = x3000-x3111;
        float x3b = x3001-x3110;
        float x3c = x3010-x3101;
        float x3d = x3100-x3011;
        float x11 = x1a-x1b+x1c+x1d;
        float x12 = x1a+x1b-x1c+x1d;
        float x13 = x1a+x1b+x1c-x1d;
        float x21 = x2a-x2b+x2c+x2d;
        float x22 = x2a+x2b-x2c+x2d;
        float x23 = x2a+x2b+x2c-x2d;
        float x31 = x3a-x3b+x3c+x3d;
        float x32 = x3a+x3b-x3c+x3d;
        float x33 = x3a+x3b+x3c-x3d;

        float u1i = u1[i3][i2][i1];
        float u2i = u2[i3][i2][i1];
        float u3i = u3[i3][i2][i1];
        float epi = ep[i3][i2][i1]*0.0625f;
        float u1p = u1i+1.0f;
        float u1u1p = u1i*u1p;
        float u2u1p = u2i*u1p;
        float u3u1p = u3i*u1p;
        float u2u3 = u2i*u3i;
        float u2smu1p = u2i*u2i-u1p;
        float u3smu1p = u3i*u3i-u1p;

        float b1 = W2*epi* (x11*u1u1p + x21*u2u1p   + x31*u3u1p);
        float b2 = W2*epi* (x11*u2u1p + x21*u2smu1p + x31*u2u3);
        float b3 = W2*epi* (x11*u3u1p + x21*u2u3    + x31*u3smu1p);
        float b4 = W0*epi* (x12*u1u1p + x22*u2u1p   + x32*u3u1p);
        float b5 = W1*epi* (x12*u2u1p + x22*u2smu1p + x32*u2u3);
        float b6 = W1*epi* (x12*u3u1p + x22*u2u3    + x32*u3smu1p);
        float b7 = W0*epi* (x13*u1u1p + x23*u2u1p   + x33*u3u1p);
        float b8 = W1*epi* (x13*u2u1p + x23*u2smu1p + x33*u2u3);
        float b9 = W1*epi* (x13*u3u1p + x23*u2u3    + x33*u3smu1p);

        float y11 = b1*u1u1p + b2*u2u1p   + b3*u3u1p;
        float y12 = b4*u1u1p + b5*u2u1p   + b6*u3u1p;
        float y13 = b7*u1u1p + b8*u2u1p   + b9*u3u1p;
        float y21 = b1*u2u1p + b2*u2smu1p + b3*u2u3;
        float y22 = b4*u2u1p + b5*u2smu1p + b6*u2u3;
        float y23 = b7*u2u1p + b8*u2smu1p + b9*u2u3;
        float y31 = b1*u3u1p + b2*u2u3    + b3*u3smu1p;
        float y32 = b4*u3u1p + b5*u2u3    + b6*u3smu1p;
        float y33 = b7*u3u1p + b8*u2u3    + b9*u3smu1p;

        float y1a = y11+y12+y13;
        float y1b = y11-y12+y13;
        float y1c = y11+y12-y13;
        float y1d = y11-y12-y13;
        float y2a = y21+y22+y23;
        float y2b = y21-y22+y23;
        float y2c = y21+y22-y23;
        float y2d = y21-y22-y23;
        float y3a = y31+y32+y33;
        float y3b = y31-y32+y33;
        float y3c = y31+y32-y33;
        float y3d = y31-y32-y33;
        y100[i1 ] += y1a;
        y100[i1m] -= y1d;
        y101[i1 ] += y1b;
        y101[i1m] -= y1c;
        y110[i1 ] += y1c;
        y110[i1m] -= y1b;
        y111[i1 ] += y1d;
        y111[i1m] -= y1a;
        y200[i1 ] += y2a;
        y200[i1m] -= y2d;
        y201[i1 ] += y2b;
        y201[i1m] -= y2c;
        y210[i1 ] += y2c;
        y210[i1m] -= y2b;
        y211[i1 ] += y2d;
        y211[i1m] -= y2a;
        y300[i1 ] += y3a;
        y300[i1m] -= y3d;
        y301[i1 ] += y3b;
        y301[i1m] -= y3c;
        y310[i1 ] += y3c;
        y310[i1m] -= y3b;
        y311[i1 ] += y3d;
        y311[i1m] -= y3a;
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  
  private static float[][][][] scopy(float[][][][] x) {
    int n4 = x.length;
    int n3 = x[0].length;
    int n2 = x[0][0].length;
    int n1 = x[0][0][0].length;
    float[][][][] y = new float[n4][n3][n2][n1];
    scopy(x,y);
    return y;
  }

  private static void scopy(float[][][][] x, float[][][][] y) {
    int n4 = x.length;
    for (int i4=0; i4<n4; i4++)
      copy(x[i4],y[i4]);
  }

  private void cleanShifts(int[][][] fm, float[][][][] r) {
    int n4 = r.length;
    for (int i4=0; i4<n4; ++i4) {
      cleanShifts(fm,r[i4]);
    }
  }

  private void cleanShifts(int[][][] fm, float[][][] x) {
    int n3 = x.length;
    int n2 = x[0].length;
    int m2 = n2-1;
    int m3 = n3-1;
    int fn = fm[0].length;
    for (int fi=0; fi<fn; ++fi) {
      int np = fm[0][fi].length;
      for (int ip=0; ip<np; ++ip) {
        int i1 = fm[0][fi][ip];
        int i2 = fm[1][fi][ip];
        int i3 = fm[2][fi][ip];
        int i2m1 = i2-1; if(i2m1<0) i2m1=0;
        int i3m1 = i3-1; if(i3m1<0) i3m1=0;
        int i2m2 = i2-2; if(i2m2<0) i2m2=0;
        int i3m2 = i3-2; if(i3m2<0) i3m2=0;
        int i2p1 = i2+1; if(i2p1>m2) i2p1=m2;
        int i3p1 = i3+1; if(i3p1>m3) i3p1=m3;
        int i2p2 = i2+2; if(i2p2>m2) i2p2=m2;
        int i3p2 = i3+2; if(i3p2>m3) i3p2=m3;
        x[i3][i2  ][i1] = x[i3][i2m2][i1];
        x[i3][i2m1][i1] = x[i3][i2m2][i1];
        x[i3][i2p1][i1] = x[i3][i2p2][i1];
        x[i3m1][i2][i1] = x[i3m2][i2][i1];
        x[i3p1][i2][i1] = x[i3p2][i2][i1];
      }
    }
  }

  public static void initializeShifts(
    int[] cn, float[][][] cu, float[][][] cx, float[][][][] r) 
  {
    if (cx!=null) {
      int ns = cn.length;
      for (int is=0; is<ns; ++is) {
        if(cn[is]>1) {
          float sum = 0.0f; 
          float cot = 0.0f;
          int np = cu[0][is].length-1;
          for (int ip=0; ip<np; ++ip) {
            if(cu[0][is][ip]>=0.0f) {
              cot += 1.0f;
              sum += cx[0][is][ip]; 
            }
          }
          float avg = sum/cot;
          float s2 = cx[1][is][np];
          float s3 = cx[2][is][np];
          float s1 = 1.0f/cx[0][is][np];
          for (int ip=0; ip<np; ++ip) {
            int u1 = round(cu[0][is][ip]);
            if(u1>=0) {
              int u2 = round(cu[1][is][ip]);
              int u3 = round(cu[2][is][ip]);
              float d1 = avg-cx[0][is][ip];
              r[0][u3][u2][u1] = d1;
              //r[1][u3][u2][u1] = -d1*s2*s1;
              //r[2][u3][u2][u1] = -d1*s3*s1;
            }
          }
        }
      }
    }
  }

  /*

  private static void constrain(float[][][] cs, float[][][][] x) {
    if (cs!=null) {
      int n4 = x.length;
      for (int i4=0; i4<n4; ++i4) 
        constrain(cs,x[i4]);
    }
  }
  */

  private static void constrain(int[] cn, float[][][] cu, float[][][] x) {
    int ns = cn.length;
    for (int is=0; is<ns; ++is) {
      if(cn[is]>1) {
        float sum = 0.0f;
        float cot = 0.0f;
        int np = cu[0][is].length-1;
        for (int ip=0; ip<np; ++ip) {
          int u1 = round(cu[0][is][ip]);
          if(u1>=0) {
            cot += 1.0f;
            int u2 = round(cu[1][is][ip]);
            int u3 = round(cu[2][is][ip]);
            sum += x[u3][u2][u1];
          }
        }
        float avg = sum/cot;
        for (int ip=0; ip<np; ++ip) {
          int u1 = round(cu[0][is][ip]);
          if(u1>=0) {
            int u2 = round(cu[1][is][ip]);
            int u3 = round(cu[2][is][ip]);
            x[u3][u2][u1] = avg;
          }
        }
      }
    }
  }

  private static void constrain2(int[] cn, float[][][] cu, float[][][] x) {
    int ns = cn.length;
    for (int is=0; is<ns; ++is) {
      if(cn[is]>1) {
        int np = cu[0][is].length-1;
        for (int ip=0; ip<np; ++ip) {
          int u1 = round(cu[0][is][ip]);
          if(u1>=0) {
            int u2 = round(cu[1][is][ip]);
            int u3 = round(cu[2][is][ip]);
            x[u3][u2][u1] = 0.0f;
          }
        }
      }
    }
  }


  private static void removeAverage(float[][][][] x) {
    int n4 = x.length;
    for (int i4=0; i4<n4; ++i4) 
      removeAverage(x[i4]);
  }


  private static void removeAverage(float[][][] x) {
    int n3 = x.length;
    int n2 = x[0].length;
    int n1 = x[0][0].length;
    float nh = (float)(n2*n3);
    for (int i1=0; i1<n1; ++i1) {
      float sumx = 0.0f;
      for (int i3=0; i3<n3; ++i3)  
        for (int i2=0; i2<n2; ++i2)  
          sumx += x[i3][i2][i1];
      float avgx = sumx/nh;
      for (int i3=0; i3<n3; ++i3) 
        for (int i2=0; i2<n2; ++i2) 
          x[i3][i2][i1] -= avgx; 
    }
  }



  private void setWeightsForSmoothing(float[][][] cs, float[][][] wp) {
    float th = 0.0f;
    //float th = 0.0f;
    int n3 = wp.length;
    int n2 = wp[0].length;
    int n1 = wp[0][0].length;
    for (int i3=0; i3<n3; ++i3) { 
      for (int i2=0; i2<n2; ++i2) { 
        for (int i1=0; i1<n1; ++i1) { 
          float wpi  = wp[i3][i2][i1]; 
          if (wpi <th)  {
            wp[i3][i2][i1]=th; 
          }
        }
      }
    }
  }

  private void countPoints(float[][][] cu, int[] cn) {
    int ns = cu[0].length; 
    zero(cn);
    for (int is=0; is<ns; ++is) {
      int np = cu[0][is].length-1;
      for (int ip=0; ip<np; ++ip) {
        float cu1 = cu[0][is][ip];
        if(cu1>=0.0f) {cn[is]+=1;}
      }
    }
  }

  private void updateConstraints(
    float[][][] wp, float[][][] cx, float[][][][] r, float[][][] cu) 
  {
    int n3 = r[0].length;
    int n2 = r[0][0].length;
    int n1 = r[0][0][0].length;
    int[] id   = new int[2];
    int[] xx   = new int[3];
    int[] ui   = new int[3];
    float[] xu = new float[3];
    setArrayValue(-1.0f,cu);
    float[][][] cd = fillfloat(500.0f,n1,n2,n3);
    for (int i3=0; i3<n3; ++i3) {
      for (int i2=0; i2<n2; ++i2) {
        for (int i1=0; i1<n1; ++i1) {
          xu[0] = i1-r[0][i3][i2][i1];
          xu[1] = i2;//-r[1][i3][i2][i1];
          xu[2] = i3;//-r[2][i3][i2][i1];
          float ds = findNearest(xu,id,xx);
          if(ds>=0.0f) {
            ui[0] = i1;
            ui[1] = i2;
            ui[2] = i3;
            if(valid(ui,xx,wp)){
              int x1 = xx[0]; 
              int x2 = xx[1]; 
              int x3 = xx[2]; 
              if(ds<cd[x3][x2][x1]) {
                int is = id[0];
                int ip = id[1];
                cu[0][is][ip] = i1;
                cu[1][is][ip] = i2;
                cu[2][is][ip] = i3;
                cd[x3][x2][x1] = ds;
              }
            }
          }
        }
      }
    }
    //cleanConstraints(n1,n2,n3,cu);
  }

  private boolean valid(int[] u, int[] x, float[][][] wp) {
    int[] uf = nearestFaultCell(u,wp);
    if(uf==null) {return false;}
    int[] xf = nearestFaultCell(x,wp);
    if(xf==null) {return false;}
    int ufu2 = uf[1]-u[1];
    int ufu3 = uf[2]-u[2];
    int xfx2 = xf[1]-x[1];
    int xfx3 = xf[2]-x[2];
    if(ufu2*xfx2+ufu3*xfx3<0){return false;}
    return true;
  }

  private void cleanConstraints(int[] cn, float[][][] wp, float[][][] cu) {
    int ns = cu[0].length;
    int[] u = new int[3];
    int[] f = new int[3];
    int[][] uf = new int[2][2];
    for (int is=0; is<ns; ++is) {
      if(cn[is]>1){
        boolean invalid = false;
        int np = cu[0][is].length-1;
        for (int ip=0; ip<np; ++ip) {
          u[0] = round(cu[0][is][ip]);
          u[1]  = round(cu[1][is][ip]);
          u[2]  = round(cu[2][is][ip]);
          f     = nearestFaultCell(u,wp);
          if(f==null){invalid=true;}
          else {
            uf[ip][0] = u[1]-f[1];
            uf[ip][1] = u[2]-f[2];
          }
        }
        if(invalid){cu[0][is][0]=-1.0f;continue;}
        int ufd = uf[0][0]*uf[1][0]+uf[0][1]*uf[1][1];
        if(ufd>=0){cu[0][is][0]=-1.0f;}
      }
    }
  }

  private int[] nearestFaultCell(int[] u, float[][][] wp) {
    int dr = 3;
    int ds = 500;
    int u1 = u[0];
    int u2 = u[1];
    int u3 = u[2];
    int n3 = wp.length;
    int n2 = wp[0].length;
    int[] f = new int[3];
    for (int d3=-dr; d3<=dr; ++d3) {
      for (int d2=-dr; d2<=dr; ++d2) {
        int i2 = u2+d2;
        int i3 = u3+d3;
        if(i2<0){i2=0;} if(i2>=n2){i2=n2-1;}
        if(i3<0){i3=0; }if(i3>=n3){i3=n3-1;}
        if(wp[i3][i2][u1]==0.0f) {
          int du2 = u2-i2;
          int du3 = u3-i3;
          int dsi = du2*du2+du3*du3;
          if(dsi<ds) {
            ds = dsi;
            f[0] = u1;
            f[1] = i2;
            f[2] = i3;
          }
        }
      }
    }
    if(ds==500){return null;}
    return f;
  }

  private void cleanConstraints(int n1, int n2, int n3, float[][][] cu) {
    int[][][] mk = new int[n3][n2][n1];
    int ns = cu[0].length;
    for (int is=0; is<ns; ++is) {
      int np = cu[0][is].length-1;
      for (int ip=0; ip<np; ++ip) {
        int u1 = round(cu[0][is][ip]);
        if(u1>=0) {
          int u2 = round(cu[1][is][ip]);
          int u3 = round(cu[2][is][ip]);
          mk[u3][u2][u1] +=1;
          if(mk[u3][u2][u1]>1) 
            cu[0][is][0]= -1.0f;
        }
      }
    }
  }

  private float findNearest(float[] xu, int[] id, int[] xx) {
    int dr = 3;
    float ds = 500.f;
    int n3 = _cm[0].length;
    int n2 = _cm[0][0].length;
    int n1 = _cm[0][0][0].length;
    float xu1 = xu[0];
    float xu2 = xu[1];
    float xu3 = xu[2];
    int x1 = round(xu1);
    int x2 = round(xu2);
    int x3 = round(xu3);
    for (int d3=-dr; d3<=dr; ++d3) {
      for (int d2=-dr; d2<=dr; ++d2) {
        for (int d1=-dr; d1<=dr; ++d1) {
          int i1 = x1+d1;
          int i2 = x2+d2;
          int i3 = x3+d3;
          if(i1<0){i1=0;} if(i1>=n1){i1=n1-1;}
          if(i2<0){i2=0;} if(i2>=n2){i2=n2-1;}
          if(i3<0){i3=0; }if(i3>=n3){i3=n3-1;}
          int is = round(_cm[0][i3][i2][i1]);
          if(is>=0) {
            float dx1 = xu1-_cm[2][i3][i2][i1];
            float dx2 = xu2-_cm[3][i3][i2][i1];
            float dx3 = xu3-_cm[4][i3][i2][i1];
            float dsi = dx1*dx1+dx2*dx2+dx3*dx3;
            if(dsi<ds) {
              ds = dsi;
              id[0] = is;
              xx[0] = i1;
              xx[1] = i2;
              xx[2] = i3;
              id[1] = round(_cm[1][i3][i2][i1]);
            }
          }
        }
      }
    }
    if(ds>4.f){return -1.f;}
    return ds;
  }

  private void setControlMap(float[][][] cx) {
    int ns = cx[0].length;
    for (int is=0; is<ns; ++is) {
      int np = cx[0][is].length-1;
      for (int ip=0; ip<np; ++ip) {
        float cx1 = cx[0][is][ip];
        float cx2 = cx[1][is][ip];
        float cx3 = cx[2][is][ip];
        int i1 = round(cx1);
        int i2 = round(cx2);
        int i3 = round(cx3);
        _cm[0][i3][i2][i1] = is;
        _cm[1][i3][i2][i1] = ip;
        _cm[2][i3][i2][i1] = cx1;
        _cm[3][i3][i2][i1] = cx2;
        _cm[4][i3][i2][i1] = cx3;
      }
    }
  }

  private void setArrayValue(float v, float[][][] x) {
    int n3 = x.length;
    int n2 = x[0].length;
    for (int i3=0; i3<n3; ++i3) {
      for (int i2=0; i2<n2; ++i2) {
        int n1 = x[i3][i2].length;
        for (int i1=0; i1<n1; ++i1) {
          x[i3][i2][i1] = v;
        }
      }
    }
  }



  private static void updateParameters(
    float[][][] r, float[][][] p, float[][][] q)
  {
    for (int i=0; i<3; ++i) // shift normal vectors and ep
      applyShiftsLinear(r,p[i],q[i]);
    normalize(q[0],q[1]);
  }

  private static void updateParameters(
    float[][][][] r, float[][][][] p, float[][][][] q) 
  {
    for (int i=0; i<3; ++i) // shift normal vectors and ep
      applyShiftsLinear(r,p[i],q[i]);
    normalize(q[0],q[1],q[2]);
  }

  // Normalize vectors
  private static void normalize(float[][] a1, float[][] a2) {
    int n1 = a1[0].length;
    int n2 = a1.length;
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        float a1i = a1[i2][i1];
        float a2i = a2[i2][i1];
        float sca = 1.0f/sqrt(a1i*a1i+a2i*a2i);
        a1[i2][i1] *= sca;
        a2[i2][i1] *= sca;
      }
    }
  }
  private static void normalize(
    final float[][][] a1, final float[][][] a2, final float[][][] a3)
  {
    final int n1 = a1[0][0].length;
    final int n2 = a1[0].length;
    final int n3 = a1.length;
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2) {
        for (int i1=0; i1<n1; ++i1) {
          float a1i = a1[i3][i2][i1];
          float a2i = a2[i3][i2][i1];
          float a3i = a3[i3][i2][i1];
          float sca = 1.0f/sqrt(a1i*a1i+a2i*a2i+a3i*a3i);
          a1[i3][i2][i1] *= sca;
          a2[i3][i2][i1] *= sca;
          a3[i3][i2][i1] *= sca;
        }
      }
    }});
  }

  /**
   * Applies the specified shifts using linear interpolation.
   * The returned array is a sampling of g(u) = f(u-r(u)).
   * @param f input array to which shifts are to be applied.
   * @param r array {r1,r2} of shifts.
   * @return array with shifts applied.
   */
  private static void applyShiftsLinear(
    float[][][] r, float[][] f, float[][] g)
  {
    int n1 = f[0].length;
    int n2 = f.length;
    float[][] r1 = r[0], r2 = r[1];
    LinearInterpolator li = new LinearInterpolator();
    li.setExtrapolation(LinearInterpolator.Extrapolation.CONSTANT);
    li.setUniform(n1,1.0,0.0,n2,1.0,0.0,f);
    for (int i2=0; i2<n2; ++i2) {
      for (int i1=0; i1<n1; ++i1) {
        g[i2][i1] = li.interpolate(i1-r1[i2][i1],i2-r2[i2][i1]);
      }
    }
  }

  /**
   * Applies the specified shifts using linear interpolation.
   * The returned array is a sampling of g(u) = f(u-r(u)).
   * @param f input array to which shifts are to be applied.
   * @param r array {r1,r2,r3} of shifts.
   * @return array with shifts applied.
   */
  private static void applyShiftsLinear( 
    float[][][][] r, float[][][] f, float[][][] g){
    final int n1 = f[0][0].length;
    final int n2 = f[0].length;
    final int n3 = f.length;
    final float[][][] gf = g;
    final float[][][] r1 = r[0], r2 = r[1], r3 = r[2];
    final LinearInterpolator li = new LinearInterpolator();
    li.setExtrapolation(LinearInterpolator.Extrapolation.CONSTANT);
    li.setUniform(n1,1.0,0.0,n2,1.0,0.0,n3,1.0,0.0,f);
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2) 
        for (int i1=0; i1<n1; ++i1) 
          gf[i3][i2][i1] = li.interpolate(
              i1-r1[i3][i2][i1],i2-r2[i3][i2][i1],i3-r3[i3][i2][i1]);
    }});
  }

  private static void applyShiftsLinear(final float[][][] mk, 
    float[][][][] r, float[][][] f, float[][][] g){
    final int n1 = f[0][0].length;
    final int n2 = f[0].length;
    final int n3 = f.length;
    final float[][][] gf = g;
    final float[][][] r1 = r[0], r2 = r[1], r3 = r[2];
    final LinearInterpolator li = new LinearInterpolator();
    li.setExtrapolation(LinearInterpolator.Extrapolation.CONSTANT);
    li.setUniform(n1,1.0,0.0,n2,1.0,0.0,n3,1.0,0.0,f);
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2) 
        for (int i1=0; i1<n1; ++i1) 
          if (mk[i3][i2][i1]!=1.0f) {
            gf[i3][i2][i1] = li.interpolate(
              i1-r1[i3][i2][i1],i2-r2[i3][i2][i1],i3-r3[i3][i2][i1]);
          }
    }});
  }



}
