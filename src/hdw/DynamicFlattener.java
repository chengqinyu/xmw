/****************************************************************************
Copyright 2012, Colorado School of Mines and others.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
****************************************************************************/
package hdw;

import edu.mines.jtk.dsp.*;
import edu.mines.jtk.util.*;
import static edu.mines.jtk.util.ArrayMath.*;
import vec.*;
import util.*;
import java.util.*;

/**
 * Seismic flattening with dynamic warping.
 * @author Xinming Wu, Colorado School of Mines
 * @version 2017.02.16
 */
public class DynamicFlattener {

  /**
   * The method used to extrapolate alignment errors.
   * Alignment errors |f[i]-g[i+l]| cannot be computed for indices
   * i and lags l for which the sum i+l is out of bounds. For such
   * indices and lags, errors are missing and must be extrapolated.
   * <p>
   * The extrapolation methods provided are designed to work best 
   * in the case where errors are low for one particular lag l, that 
   * is, when the sequences f and g are related by a constant shift.
   */
  public enum ErrorExtrapolation {
    /**
     * For each lag, extrapolate alignment errors using the nearest
     * error not missing for that lag.
     * <p>
     * This is the default extrapolation method.
     */
    NEAREST,
    /**
     * For each lag, extrapolate alignment errors using the average
     * of all errors not missing for that lag.
     */
    AVERAGE,
    /**
     * For each lag, extrapolate alignment errors using a reflection
     * of nearby errors not missing for that lag.
     */
    REFLECT
  }

  /**
   * Constructs a dynamic warping for specified bounds on shifts.
   * @param shiftMin lower bound on shift u.
   * @param shiftMax upper bound on shift u.
   */
  public DynamicFlattener(int shiftMin, int shiftMax) {
    Check.argument(shiftMax-shiftMin>1,"shiftMax-shiftMin>1");
    _lmin = shiftMin;
    _lmax = shiftMax;
    _nl = 1+_lmax-_lmin;
    _si = new SincInterpolator();
    _extrap = ErrorExtrapolation.AVERAGE;
  }

  public void setGate(int ud) {
    _ud = ud;
  }

  public void setShiftMax(int shiftMin, int shiftMax) {
    Check.argument(shiftMax-shiftMin>1,"shiftMax-shiftMin>1");
    _lmin = shiftMin;
    _lmax = shiftMax;
    _nl = 1+_lmax-_lmin;
    _si = new SincInterpolator();
    _extrap = ErrorExtrapolation.AVERAGE;
  }


  /**
   * Sets bound on strain for all dimensions. Must be in (0,1].
   * The actual bound on strain is 1.0/ceil(1.0/strainMax), which
   * is less than the specified strainMax when 1.0/strainMax is not
   * an integer. The default bound on strain is 1.0 (100%).
   * @param strainMax the bound, a value less than or equal to one.
   */
  public void setStrainMax(double strainMax) {
    Check.argument(strainMax<=1.0,"strainMax<=1.0");
    Check.argument(strainMax>0.0,"strainMax>0.0");
    setStrainMax(strainMax,strainMax);
  }

  /**
   * Sets bound on strains in 1st and 2nd dimensions.
   * @param strainMax1 bound on strain in the 1st dimension.
   * @param strainMax2 bound on strain in the 2nd dimension.
   */
  public void setStrainMax(double strainMax1, double strainMax2) {
    Check.argument(strainMax1<=1.0,"strainMax1<=1.0");
    Check.argument(strainMax2<=1.0,"strainMax2<=1.0");
    Check.argument(strainMax1>0.0,"strainMax1>0.0");
    Check.argument(strainMax2>0.0,"strainMax2>0.0");
    setStrainMax(strainMax1,strainMax2,strainMax2);
  }

  /**
   * Sets bound on strains in 1st, 2nd and 3rd dimensions.
   * @param strainMax1 bound on strain in the 1st dimension.
   * @param strainMax2 bound on strain in the 2nd dimension.
   * @param strainMax3 bound on strain in the 3rd dimension.
   */
  public void setStrainMax(
    double strainMax1, double strainMax2, double strainMax3) 
  {
    Check.argument(strainMax1<=1.0,"strainMax1<=1.0");
    Check.argument(strainMax2<=1.0,"strainMax2<=1.0");
    Check.argument(strainMax3<=1.0,"strainMax3<=1.0");
    Check.argument(strainMax1>0.0,"strainMax1>0.0");
    Check.argument(strainMax2>0.0,"strainMax2>0.0");
    Check.argument(strainMax3>0.0,"strainMax3>0.0");
    _bstrain1 = (int)ceil(1.0/strainMax1);
    _bstrain2 = (int)ceil(1.0/strainMax2);
    _bstrain3 = (int)ceil(1.0/strainMax3);
    updateSmoothingFilters();
  }

  /**
   * Sets the method used to extrapolate alignment errors.
   * Extrapolation is necessary when the sum i+l of sample index
   * i and lag l is out of bounds. The default method is to use 
   * the error computed for the nearest index i and the same lag l.
   * @param ee the error extrapolation method.
   */
  public void setErrorExtrapolation(ErrorExtrapolation ee) {
    _extrap = ee;
  }

  /**
   * Sets the exponent used to compute alignment errors |f-g|^e.
   * The default exponent is 2.
   * @param e the exponent.
   */
  public void setErrorExponent(double e) {
    _epow = (float)e;
  }

  /**
   * Sets the number of nonlinear smoothings of alignment errors.
   * In dynamic warping, alignment errors are smoothed the specified 
   * number of times, along all dimensions (in order 1, 2, ...), 
   * before estimating shifts by accumulating and backtracking along 
   * only the 1st dimension. 
   * <p> 
   * The default number of smoothings is zero, which is best for 1D
   * sequences. For 2D and 3D images, two smoothings are recommended.
   * @param esmooth number of nonlinear smoothings.
   */
  public void setErrorSmoothing(int esmooth) {
    _esmooth = esmooth;
  }

  /**
   * Sets extent of smoothing filters used to smooth shifts.
   * Half-widths of smoothing filters are inversely proportional to
   * strain limits, and are scaled by the specified factor. Default 
   * factor is zero, for no smoothing.
   * @param usmooth extent of smoothing filter in all dimensions.
   */
  public void setShiftSmoothing(double usmooth) {
    setShiftSmoothing(usmooth,usmooth);
  }

  /**
   * Sets extents of smoothing filters used to smooth shifts.
   * Half-widths of smoothing filters are inversely proportional to
   * strain limits, and are scaled by the specified factors. Default 
   * factors are zero, for no smoothing.
   * @param usmooth1 extent of smoothing filter in 1st dimension.
   * @param usmooth2 extent of smoothing filter in 2nd dimension.
   */
  public void setShiftSmoothing(double usmooth1, double usmooth2) {
    setShiftSmoothing(usmooth1,usmooth2,usmooth2);
  }

  /**
   * Sets extents of smoothing filters used to smooth shifts.
   * Half-widths of smoothing filters are inversely proportional to
   * strain limits, and are scaled by the specified factors. Default 
   * factors are zero, for no smoothing.
   * @param usmooth1 extent of smoothing filter in 1st dimension.
   * @param usmooth2 extent of smoothing filter in 2nd dimension.
   * @param usmooth3 extent of smoothing filter in 3rd dimension.
   */
  public void setShiftSmoothing(
    double usmooth1, double usmooth2, double usmooth3) 
  {
    _usmooth1 = usmooth1;
    _usmooth2 = usmooth2;
    _usmooth3 = usmooth3;
    updateSmoothingFilters();
  }

  public void setWindow(int d, int wind) {
    _dw = d;
    _wind = wind;
  }

  /**
   * Computes and returns shifts for specified sequences.
   * @param f array for the sequence f.
   * @param g array for the sequence g.
   * @return array of shifts u.
   */
  public float[] findShifts(float[] f, float[] g) {
    float[] u = like(f);
    findShifts(f,g,u);
    return u;
  }

  /**
   * Computes and returns shifts for specified images.
   * @param f array for the image f.
   * @param g array for the image g.
   * @return array of shifts u.
   */
  public float[][] findShifts(float[][] f, float[][] g) {
    float[][] u = like(f);
    findShifts(f,g,u);
    return u;
  }

  /**
   * Computes and returns shifts for specified images.
   * @param f array for the image f.
   * @param g array for the image g.
   * @return array of shifts u.
   */
  public float[][][] findShifts(float[][][] f, float[][][] g) {
    float[][][] u = like(f);
    findShifts(f,g,u);
    return u;
  }

  /**
   * Computes and returns 1D shifts u for specified 2D images f and g.
   * This method is useful in the case that shifts vary only slightly 
   * (or perhaps not at all) in the 2nd image dimension.
   * @param f array[n2][n1] for the image f.
   * @param g array[n2][n1] for the image g.
   * @return array[n1] of shifts u.
   */
  public float[] findShifts1(float[][] f, float[][] g) {
    float[] u = like(f[0]);
    findShifts1(f,g,u);
    return u;
  }

  /**
   * Computes and returns 1D shifts u for specified 3D images f and g.
   * This method is useful in the case that shifts vary only slightly 
   * (or perhaps not at all) in the 2nd and 3rd image dimensions.
   * @param f array[n3][n2][n1] for the image f.
   * @param g array[n3][n2][n1] for the image g.
   * @return array[n1] of shifts u.
   */
  public float[] findShifts1(float[][][] f, float[][][] g) {
    float[] u = like(f[0][0]);
    findShifts1(f,g,u);
    return u;
  }

  /**
   * Computes shifts for specified sequences.
   * @param f input array for the sequence f.
   * @param g input array for the sequence g.
   * @param u output array of shifts u.
   */
  public void findShifts(float[] f, float[] g, float[] u) {
    float[][] e = computeErrors(f,g);
    for (int is=0; is<_esmooth; ++is)
      smoothErrors(e,e);
    float[][] d = accumulateForward(e);
    backtrackReverse(d,e,u);
    smoothShifts(u,u);
  }

  public float[][][] flatten(
    int c2, int c3, float wt, 
    float[][][] el, float[][][] fx, float[][][] ux) {
    int n3 = fx.length;
    int n2 = fx[0].length;
    int n1 = fx[0][0].length;
    float[][] wx = new float[n3][n2];
    float[][][] gx = new float[n3][n2][n1];
    gx[c3][c2] = fx[c3][c2];
    for (int i3=0; i3<n3; ++i3) {
    for (int i2=0; i2<n2; ++i2) {
      float ws = 0f;
      float[] el32 = el[i3][i2];
      for (int i1=0; i1<n1; ++i1)
        ws += el32[i1];
      wx[i3][i2] = ws/n1;
    }}
    int np = n2*n3;
    int pd[] = new int[np];
    float ds[] = new float[np];
    int s = getPath(c2,c3,wx,ds,pd);
    int[] id = rampint(0,1,np);
    quickIndexSort(ds,id);
    for (int ip=0; ip<np; ++ip) {
      int x = id[ip];
      while (x!=s) {
      }

    }
    return gx;
  }

  private int getPath(int p2, int p3, float[][] wx, float[] ds, int[] pd) {
    Dijkstra dk = new Dijkstra(wx);
    int s = dk.coord2Index(p2,p3);
    dk.apply(s,ds,pd);
    return s;
  }

  public float[][][] flattenILX(int p2, int p3, float[][][] el, 
    float[][][] fx, float[][][] ux) 
  {
    final int n3 = fx.length;
    final int n2 = fx[0].length;
    final int n1 = fx[0][0].length;
    final float[][][] gx = new float[n3][n2][n1];
    final float[][] sm = new float[n3][n2];
    final int[] ct = new int[1];
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      gx[i3] =flatten(el[i3],fx[i3],ux[i3],sm[i3]);
      ct[0] += 1;
      System.out.println(round(ct[0]*100f/n3)+"% done...");
    }});
    RecursiveExponentialFilter rgf = new RecursiveExponentialFilter(2);
    rgf.apply2(ux,ux);
    //rgf.apply3(ux,ux);
    return flattenWithHorizons(ux,fx);
    //return gx;
  }


  public float[][][] flattenIL(int p2, int p3, float[][][] el, 
    float[][][] fx, float[][][] ux) 
  {
    final int n3 = fx.length;
    final int n2 = fx[0].length;
    final int n1 = fx[0][0].length;
    final float[][][] ft = copy(fx);
    RecursiveGaussianFilter rgf = new RecursiveGaussianFilter(2);
    rgf.apply000(fx,fx);
    final float[][][] gx = new float[n3][n2][n1];
    final float[][] sm = new float[n3][n2];
    final int[] ct = new int[1];
    final float[][] fx3 = fx[p3];
    final float[][] el3 = el[p3];
    final float[][] ux3 = ux[p3];
    fx[p3] = flatten(el3,fx3,ux3,sm[p3]);
    _lmin = -10;
    _lmax =  10;
    Parallel.loop(400,new Parallel.LoopInt() {
    public void compute(int i2) {
      float[][] fx2 = new float[n3][n1];
      float[][] el2 = new float[n3][n1];
      float[][] ux2 = new float[n3][n1];
      float[] sm2 = new float[n3];
      int b3 = p3;
      int e3 = n3-1;
      int d3 = 1;
      if(b3>0){e3=0;d3=-1;}
      int k3 = 0;
      for (int i3=b3; i3!=e3; i3+=d3) {
        fx2[k3] = fx[i3][i2];
        el2[k3] = el[i3][i2];
        ux2[k3] = ux[i3][i2];
        sm2[k3] = sm[i3][i2];
        k3++;
      }
      ct[0] += 1;
      System.out.println(round(ct[0]*100f/n2)+"% done...");
      float[][] gx2 =flatten(el2,fx2,ux2,sm2);
      k3 = 0;
      for (int i3=b3; i3!=e3; i3+=d3) {
        gx[i3][i2] = gx2[k3];
        ux[i3][i2] = ux2[k3];
        k3++;
      }
    }});
    RecursiveExponentialFilter ref = new RecursiveExponentialFilter(2);
    ref.apply2(ux,ux);
    ref.apply3(ux,ux);
    return flattenWithHorizons(ux,ft);
    //return gx;
  }

  public float[][][] flattenXLm(int p2, int p3, float[][][] el, 
    float[][][] fs, float[][][] fx, float[][][] ux) 
  {
    final int n3 = fx.length;
    final int n2 = fx[0].length;
    final int n1 = fx[0][0].length;
    final float[][] sm = new float[n3][n2];
    final float[][][] gx = new float[n3][n2][n1];
    final int[] ct = new int[1];
    final float[][] fx2 = new float[n3][n1];
    final float[][] fs2 = new float[n3][n1];
    final float[][] ux2 = new float[n3][n1];
    final float[][] el2 = fillfloat(1f,n1,n3);
    sm[p3][p2] = 1f;
    final float[] sm2 = new float[n3];
    int k3 = 0;
    for (int i3=n3-1; i3>=0; --i3) {
      fs2[k3] = fs[i3][p2];
      fx2[k3] = fx[i3][p2];
      el2[k3] = el[i3][p2];
      sm2[k3] = sm[i3][p2];
      k3++;
    }
    float[][] gs2 = flatten(el2,fs2,ux2,sm2);
    k3 = 0;
    for (int i3=n3-1; i3>=0; --i3) {
      ux[i3][p2] = ux2[k3];
      fs[i3][p2] = gs2[k3];
      gx[i3][p2] = applyShifts(ux2[k3],fx[i3][p2]);
      k3++;
    }
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      ct[0] += 1;
      System.out.println(ct[0]*100f/n3+"% done...");
      float[] sf = new float[n2-p2];
      float[][] ef = new float[n2-p2][n1];
      float[][] ff = new float[n2-p2][n1];
      float[][] uf = new float[n2-p2][n1];
      for (int i2=p2; i2<n2; ++i2) {
        ef[i2-p2] = el[i3][i2];
        ff[i2-p2] = fs[i3][i2];
        uf[i2-p2] = ux[i3][i2];
        sf[i2-p2] = sm[i3][i2];
      }
      float[][] gf = flatten(ef,ff,uf,sf);
      for (int i2=p2; i2<n2; ++i2)  
        gx[i3][i2] = applyShifts(uf[i2-p2],fx[i3][i2]);

      float[] sb = new float[p2+1];
      float[][] eb = new float[p2+1][n1];
      float[][] fb = new float[p2+1][n1];
      float[][] ub = new float[p2+1][n1];
      int k2=0;
      for (int i2=p2; i2>=0; --i2) {
        eb[k2] = el[i3][i2];
        fb[k2] = fs[i3][i2];
        ub[k2] = ux[i3][i2];
        sb[k2] = sm[i3][i2];
        k2++;
      }
      float[][] gb = flatten(eb,fb,ub,sb);
      k2 = 0;
      for (int i2=p2; i2>=0; --i2) {
        el[i3][i2]=eb[k2];
        fs[i3][i2]=fb[k2];
        ux[i3][i2]=ub[k2];
        sm[i3][i2]=sb[k2];
        //gx[i3][i2]=gb[k2];
        gx[i3][i2] = applyShifts(ub[k2],fx[i3][i2]);
        k2++;
      }
    }});
    return gx;
  }


  public float[][][] flattenXL(int p2, int p3, float[][][] el, 
    float[][][] fx, float[][][] ux) 
  {
    final int n3 = fx.length;
    final int n2 = fx[0].length;
    final int n1 = fx[0][0].length;
    final float[][] sm = new float[n3][n2];
    final float[][][] gx = new float[n3][n2][n1];
    final int[] ct = new int[1];
    final float[][] fx2 = new float[n3][n1];
    final float[][] el2 = new float[n3][n1];
    final float[][] ux2 = new float[n3][n1];
    sm[p3][p2] = 1f;
    final float[] sm2 = new float[n3];
    for (int i3=0; i3<n3; ++i3) {
      fx2[i3] = fx[i3][0];
      el2[i3] = el[i3][0];
    }
    float[][] gx2 = flatten(el2,fx2,ux2,sm2);
    for (int i3=0; i3<n3; ++i3) {
      ux[i3][0] = ux2[i3];
      fx[i3][0] = gx2[i3];
    }
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      ct[0] += 1;
      System.out.println(ct[0]*100f/n3+"% done...");
      gx[i3]=flatten(el[i3],fx[i3],ux[i3],sm[i3]);
    }});
    /*
    float[][][] hs = new float[n1][n3][n2];
    float[][][] h3 = new float[n1][n3][n2];
    float[][][] h2 = new float[n1][n3][n2];
    RecursiveGaussianFilter rgf = new RecursiveGaussianFilter(2);
    rgf.applyX1X(hs,h3);
    rgf.applyXX1(hs,h2);
    float[][][] hd = add(abs(h2),abs(h3));
    float[][][] ut = new float[n1][n3][n2];
    for (int i3=0; i3<n3; ++i3)
    for (int i2=0; i2<n2; ++i2)
    for (int i1=0; i1<n1; ++i1)
      ut[i1][i3][i2] = ux[i3][i2][i1];
    return flattenWithHorizons(ut,fx);
    */
    return gx;
  }

  public float[][][] refineIL(
    float[][][] fx, float[][][] ux) {
    _ud = 5;
    _lmin = -5;
    _lmax =  5;
    //_bstrain1 = (int)ceil(1.0/0.25);;
    final int n3 = fx.length;
    final int n2 = fx[0].length;
    final int n1 = fx[0][0].length;
    final float[][][] gx = new float[n3][n2][n1];
    final int[] ct = new int[1];
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      ct[0] += 1;
      float[][] fx2 = new float[n3][n1];
      for (int i3=0; i3<n3; ++i3)
        fx2[i3] = fx[i3][i2];
      float[][] el2 = fillfloat(0f,n1,n3);
      float[][] ux2 = new float[n3][n1];
      float[] sm2 = fillfloat(1f,n3);
      System.out.println(ct[0]*100f/n2+"% done...");
      float[][] gx2 =flatten(el2,fx2,ux2,sm2);
      for (int i3=0; i3<n3; ++i3) {
        gx[i3][i2]  = gx2[i3];
      }
    }});
    return gx;
  }

  public float[][] flattenReverse(
    float[][] el, float[][] fx, float[][] ux, float[] sm) 
  {
    int n2 = fx.length;
    int n1 = fx[0].length;
    float[][] gx = new float[n2][n1];
    gx[n2-1] = fx[n2-1];
    float[] up = findShifts(fx[n2-1],fx[n2-2]);
    gx[n2-2] = applyShifts(up,fx[n2-2]);
    sm[n2-2] = correlate(gx[0],gx[n2-2]);
    for (int i1=0; i1<n1; ++i1)
      ux[n2-2][i1] = up[i1];
    for (int i2=n2-3; i2>=0; --i2) {
      float mk = 0f;
      float[][] e2 = fillfloat(mk,_nl,n1);
      computeErrorsReverse(i2,up,el[i2],sm,gx,fx[i2],e2);
      findShifts(mk,e2,up);
      gx[i2] = applyShifts(up,fx[i2]);
      sm[i2] = correlate(gx[0],gx[i2]);
      for (int i1=0; i1<n1; ++i1) {
        float xp = up[i1];
        ux[i2][i1] =  xp;
      }
    }
    return gx;
  }


  public float[][] flatten(
    float[][] el, float[][] fx, float[][] ux, float[] sm) 
  {
    int n2 = fx.length;
    int n1 = fx[0].length;
    float[][] gx = new float[n2][n1];
    gx[0] = fx[0];
    float[] up = findShifts(fx[0],fx[1]);
    gx[1] = applyShifts(up,fx[1]);
    for (int i1=0; i1<n1; ++i1)
      ux[1][i1] = up[i1];
    for (int i2=2; i2<n2; ++i2) {
      float mk = 0f;
      float[][] e2 = fillfloat(mk,_nl,n1);
      computeErrors(i2,up,el[i2],sm,gx,fx[i2],e2);
      findShifts(mk,e2,up);
      //int umin = round(min(up))-20;
      //int umax = round(max(up))+20;
      //setShiftMax(umin,umax);
      gx[i2] = applyShifts(up,fx[i2]);
      sm[i2] = correlate(gx[0],gx[i2]);
      //System.out.println("smi="+sm[i2]);
      for (int i1=0; i1<n1; ++i1) {
        float xp = up[i1];//+i1;
        ux[i2][i1] =  xp;
      }
    }
    return gx;
  }


  private void findShifts(float mk, float[][] e2, float[] up) {
    int n1 = e2.length;
    float emax = max(e2);
    for (int i1=0; i1<n1; ++i1) 
    for (int il=0; il<_nl; ++il) 
      if(e2[i1][il]==mk) 
        e2[i1][il] = emax;
    normalizeErrors(e2);
    for (int is=0; is<_esmooth; ++is)
      smoothErrors(e2,e2);
    float[][] d = accumulateForward(e2);
    backtrackReverse(d,e2,up);
    smoothShifts(up,up);
  }



  public void refine(float[][] fx, float[][] us) {
    int n2 = fx.length;
    int n1 = fx[0].length;
    DynamicWarping dw = new DynamicWarping(-10,10);
    dw.setStrainMax(0.25,0.5);
    dw.setErrorSmoothing(3);
    float[][] gx = flattenWithHorizons(us,fx);
    //RecursiveGaussianFilterP rgf = new RecursiveGaussianFilterP(4);
    //rgf.applyX0(gx,gx);
    float[][] gr = new float[n2][n1];
    for (int i2=0; i2<n2; ++i2)
      gr[i2] = gx[0];
    float[][] ux = dw.findShifts(gr,gx);
    for (int i2=0; i2<n2; ++i2) {
    for (int i1=0; i1<n1; ++i1) {
      us[i1][i2] += ux[i2][i1];
    }}
  }

  public float[][] flattenWithHorizons(float[][] hs, float[][] fx) {
    int n2 = fx.length;
    int n1 = fx[0].length;
    float[][] gx = new float[n2][n1];
    Sampling s1 = new Sampling(n1);
    SincInterpolator si = new SincInterpolator();
    for (int i2=0; i2<n2; ++i2) {
      float[] fx2 = fx[i2];
      float[] gx2 = gx[i2];
    for (int i1=0; i1<n1; ++i1) {
      gx2[i1]=si.interpolate(s1,fx2,hs[i1][i2]);
    }}
    return gx;
  }

  public float[][][] flattenWithHorizonsX(float[][][] ux, float[][][] fx) {
    int n3 = fx.length;
    int n2 = fx[0].length;
    int n1 = fx[0][0].length;
    float[][][] gx = new float[n3][n2][n1];
    Sampling s1 = new Sampling(n1);
    SincInterpolator si = new SincInterpolator();
    for (int i3=0; i3<n3; ++i3) {
    for (int i2=0; i2<n2; ++i2) {
      float[] fx32 = fx[i3][i2];
      float[] gx32 = gx[i3][i2];
    for (int i1=0; i1<n1; ++i1) {
      gx32[i1]=si.interpolate(s1,fx32,ux[i1][i3][i2]);
    }}}
    return gx;
  }


  public float[][][] flattenWithHorizons(float[][][] ux, float[][][] fx) {
    int n3 = fx.length;
    int n2 = fx[0].length;
    int n1 = fx[0][0].length;
    float[][][] gx = new float[n3][n2][n1];
    Sampling s1 = new Sampling(n1);
    SincInterpolator si = new SincInterpolator();
    for (int i3=0; i3<n3; ++i3) {
    for (int i2=0; i2<n2; ++i2) {
      float[] fx32 = fx[i3][i2];
      float[] gx32 = gx[i3][i2];
    for (int i1=0; i1<n1; ++i1) {
      gx32[i1]=si.interpolate(s1,fx32,ux[i1][i2][i3]);
    }}}
    return gx;
  }



  public void smoothHorizon(float sig, float[][] el, float[][] hz) {
    int n2 = hz.length; 
    int n1 = hz[0].length; 
    float[][] b = new float[n2][n1];
    float[][] r = new float[n2][n1];
    float[][] w = new float[n2][n1];
    makeRhsWeights(hz,el,b,w);
    VecArrayFloat2 vb = new VecArrayFloat2(b);
    VecArrayFloat2 vr = new VecArrayFloat2(r);
    Smoother2 smoother2 = new Smoother2(sig,sig);
    A2 a2 = new A2(smoother2,w);
    CgSolver cs = new CgSolver(0.001,200);
    smoother2.applyTranspose(b);
    cs.solve(a2,vb,vr);
    copy(r,hz);
  }

    // Conjugate-gradient operators.
  private static class A2 implements CgSolver.A {
    A2(Smoother2 s2, float[][] wp) 
    {
      _s2 = s2;
      _wp = wp;
      float n2 = wp.length;
      float n1 = wp[0].length;
      _sc = sum(wp)/(n1*n2);
    }
    public void apply(Vec vx, Vec vy) {
      VecArrayFloat2 v2x = (VecArrayFloat2)vx;
      VecArrayFloat2 v2y = (VecArrayFloat2)vy;
      float[][] x = v2x.getArray();
      float[][] y = v2y.getArray();
      float[][] z = copy(x);
      v2y.zero();
      _s2.apply(z);
      addAndScale(-_sc,z,y);
      applyLhs(_wp,z,y);
      _s2.applyTranspose(y);
      addAndScale( _sc,x,y);
    }
    private float _sc;
    private Smoother2 _s2;
    private float[][] _wp;
  }

  private void makeRhsWeights(
    float[][] hz, float[][] el, float[][] b, float[][] w) 
  {
    int n2 = el.length;
    int n1 = el[0].length;
    for (int i2=0; i2<n2; ++i2) {
    for (int i1=0; i1<n1; ++i1) {
      float wi = el[i2][i1];
      float ws = wi*wi;
      b[i2][i1] = hz[i2][i1]*ws;
      w[i2][i1] = ws;
    }}
  }

  private static void addAndScale(float sc, float[][] x, float[][] y) {
    int n2 = x.length;
    int n1 = x[0].length;
    for (int i2=0; i2<n2; ++i2) {
    for (int i1=0; i1<n1; ++i1) {
      y[i2][i1] += sc*x[i2][i1];
    }}
  }
    // Smoother used as a preconditioner. After smoothing, enforces zero-shift
  // boundary conditions at top and bottom.
  private static class Smoother2 {
    public Smoother2(float sigma1, float sigma2) {
      _sigma1 = sigma1;
      _sigma2 = sigma2;
    }
    public void apply(float[][] x) {
      smooth2(_sigma2,x);
      smooth1(_sigma1,x);
    }
    public void applyTranspose(float[][] x) {
      smooth1(_sigma1,x);
      smooth2(_sigma2,x);
    }
    private float _sigma1,_sigma2;
  }


  // Smoothing for dimension 2.
  private static void smooth1(float sigma, float[][] x) {
    if (sigma<1.0f)
      return;
    RecursiveExponentialFilter.Edges edges =
      RecursiveExponentialFilter.Edges.OUTPUT_ZERO_SLOPE;
    RecursiveExponentialFilter ref = new RecursiveExponentialFilter(sigma);
    ref.setEdges(edges);
    ref.apply1(x,x);
  }


  // Smoothing for dimension 2.
  private static void smooth2(float sigma, float[][] x) {
    if (sigma<1.0f)
      return;
    RecursiveExponentialFilter.Edges edges =
      RecursiveExponentialFilter.Edges.OUTPUT_ZERO_SLOPE;
    RecursiveExponentialFilter ref = new RecursiveExponentialFilter(sigma);
    ref.setEdges(edges);
    ref.apply2(x,x);
  }



  private static void applyLhs(float[][] wp, float[][] x, float[][] y) {
    int n2 = wp.length;
    int n1 = wp[0].length;
    for (int i2=0; i2<n2; ++i2)
    for (int i1=0; i1<n1; ++i1)
      y[i2][i1] += wp[i2][i1]*x[i2][i1];
  }


  /**
   * Computes shifts for specified images.
   * @param f input array for the image f.
   * @param g input array for the image g.
   * @param u output array of shifts u.
   */
  public void findShifts(float[][] f, float[][] g, float[][] u) {
    final float[][][] e = computeErrors(f,g);
    final int nl = e[0][0].length;
    final int n1 = e[0].length;
    final int n2 = e.length;
    final float[][] uf = u;
    for (int is=0; is<_esmooth; ++is)
      smoothErrors(e,e);
    final Parallel.Unsafe<float[][]> du = new Parallel.Unsafe<float[][]>();
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      float[][] d = du.get();
      if (d==null) du.set(d=new float[n1][nl]);
      accumulateForward(e[i2],d);
      backtrackReverse(d,e[i2],uf[i2]);
    }});
    smoothShifts(u,u);
  }

  /**
   * Computes shifts for specified images.
   * @param f input array for the image f.
   * @param g input array for the image g.
   * @param u output array of shifts u.
   */
  public void findShifts(float[][][] f, float[][][] g, float[][][] u) {
    int n1 = f[0][0].length;
    int n2 = f[0].length;
    int n3 = f.length;
    OverlappingWindows2 ow = 
      new OverlappingWindows2(n2,n3,_owl2,_owl3,_owf2,_owf3);
    int m2 = ow.getM1();
    int m3 = ow.getM2();
    int l2 = ow.getL1();
    int l3 = ow.getL2();
    float[][][] fw = new float[l3][l2][];
    float[][][] gw = new float[l3][l2][];
    float[][][] uw = new float[l3][l2][n1];
    float[][][][] ew = new float[l3][l2][n1][_nl];
    for (int k3=0; k3<m3; ++k3) {
      int i3 = ow.getI2(k3);
      for (int k2=0; k2<m2; ++k2) {
        int i2 = ow.getI1(k2);
        for (int j3=0; j3<l3; ++j3) {
          for (int j2=0; j2<l2; ++j2) {
            fw[j3][j2] = f[i3+j3][i2+j2];
            gw[j3][j2] = g[i3+j3][i2+j2];
          }
        }
        computeErrors(fw,gw,ew);
        normalizeErrors(ew);
        for (int is=0; is<_esmooth; ++is)
          smoothErrors(ew);
        computeShifts(ew,uw);
        for (int j3=0; j3<l3; ++j3) {
          for (int j2=0; j2<l2; ++j2) {
            float wij = ow.getWeight(i2,i3,j2,j3);
            float[] u32 = u[i3+j3][i2+j2];
            for (int i1=0; i1<n1; ++i1)
              u32[i1] += wij*uw[j3][j2][i1];
          }
        }
      }
    }
    smoothShifts(u);
  }

  /**
   * Computes 1D shifts u for specified 2D images f and g.
   * This method is useful in the case that shifts vary only slightly 
   * (or perhaps not at all) in the 2nd image dimension.
   * @param f input array[n2][n1] for the image f.
   * @param g input array[n2][n1] for the image g.
   * @param u output array[n1] of shifts u.
   */
  public void findShifts1(float[][] f, float[][] g, float[] u) {
    float[][] e = computeErrors1(f,g);
    for (int is=0; is<_esmooth; ++is)
      smoothErrors(e,e);
    float[][] d = accumulateForward(e);
    backtrackReverse(d,e,u);
    smoothShifts(u,u);
  }

  /**
   * Computes 1D shifts u for specified 3D images f and g.
   * This method is useful in the case that shifts vary only slightly 
   * (or perhaps not at all) in the 2nd and 3rd image dimensions.
   * @param f input array[n3][n2][n1] for the image f.
   * @param g input array[n3][n2][n1] for the image g.
   * @param u output array[n1] of shifts u.
   */
  public void findShifts1(float[][][] f, float[][][] g, float[] u) {
    float[][] e = computeErrors1(f,g);
    for (int is=0; is<_esmooth; ++is)
      smoothErrors(e,e);
    float[][] d = accumulateForward(e);
    backtrackReverse(d,e,u);
    smoothShifts(u,u);
  }

  /**
   * Returns a sequence warped by applying specified shifts.
   * @param u array of shifts.
   * @param g array for the sequence to be warped.
   * @return array for the warped sequence.
   */
  public float[] applyShifts(float[] u, float[] g) {
    float[] h = like(g);
    applyShifts(u,g,h);
    return h;
  }

  /**
   * Returns an image warped by applying specified shifts.
   * @param u array of shifts.
   * @param g array for the image to be warped.
   * @return array for the warped image.
   */
  public float[][] applyShifts(float[][] u, float[][] g) {
    float[][] h = like(g);
    applyShifts(u,g,h);
    return h;
  }

  /**
   * Returns an image warped by applying specified shifts.
   * @param u array of shifts.
   * @param g array for the image to be warped.
   * @return array for the warped image.
   */
  public float[][][] applyShifts(float[][][] u, float[][][] g) {
    float[][][] h = like(g);
    applyShifts(u,g,h);
    return h;
  }

  /**
   * Computes a sequence warped by applying specified shifts.
   * @param u input array of shifts.
   * @param g input array for the sequence to be warped.
   * @param h output array for the warped sequence.
   */
  public void applyShifts(float[] u, float[] g, float[] h) {
    int n1 = u.length;
    for (int i1=0; i1<n1; ++i1) {
      h[i1] = _si.interpolate(n1,1.0,0.0,g,i1+u[i1]);
    }
  }

  /**
   * Computes an image warped by applying specified shifts.
   * @param u input array of shifts.
   * @param g input array for the image to be warped.
   * @param h output array for the warped image.
   */
  public void applyShifts(float[][] u, float[][] g, float[][] h) {
    final int n1 = u[0].length;
    final int n2 = u.length;
    final float[][] uf = u;
    final float[][] gf = g;
    final float[][] hf = h;
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      for (int i1=0; i1<n1; ++i1) {
        hf[i2][i1] = _si.interpolate(n1,1.0,0.0,gf[i2],i1+uf[i2][i1]);
      }
    }});
  }

  /**
   * Computes an image warped by applying specified shifts.
   * @param u input array of shifts.
   * @param g input array for the image to be warped.
   * @param h output array for the warped image.
   */
  public void applyShifts(float[][][] u, float[][][] g, float[][][] h) {
    int n3 = u.length;
    final float[][][] uf = u;
    final float[][][] gf = g;
    final float[][][] hf = h;
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      applyShifts(uf[i3],gf[i3],hf[i3]);
    }});
  }

  ///////////////////////////////////////////////////////////////////////////
  // for research and atypical applications

  /**
   * Returns normalized alignment errors for all samples and lags.
   * The number of lags nl = 1+shiftMax-shiftMin. Lag indices 
   * il = 0, 1, 2, ..., nl-1 correspond to integer shifts in 
   * [shiftMin,shiftMax]. Alignment errors are a monotonically
   * increasing function of |f[i1]-g[i1+il+shiftMin]|.
   * @param f array[n1] for the sequence f[i1].
   * @param g array[n1] for the sequence g[i1].
   * @return array[n1][nl] of alignment errors.
   */
  public float[][] computeErrors(float[] f, float[] g) {
    int n1 = f.length;
    float[][] e = new float[n1][_nl];
    computeErrors(f,g,e);
    normalizeErrors(e);
    return e;
  }

  /**
   * Returns normalized alignment errors for all samples and lags.
   * The number of lags nl = 1+shiftMax-shiftMin. Lag indices 
   * il = 0, 1, 2, ..., nl-1 correspond to integer shifts in 
   * [shiftMin,shiftMax]. Alignment errors are a monotonically
   * increasing function of |f[i2][i1]-g[i2][i1+il+shiftMin]|.
   * @param f array[n2][n1] for the image f[i2][i1].
   * @param g array[n2][n1] for the image g[i2][i1].
   * @return array[n2][n1][nl] of alignment errors.
   */
  public float[][][] computeErrors(float[][] f, float[][] g) {
    final int n1 = f[0].length;
    final int n2 = f.length;
    final float[][] ff = f;
    final float[][] gf = g;
    final float[][][] ef = new float[n2][n1][_nl];
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      computeErrors(ff[i2],gf[i2],ef[i2]);
    }});
    normalizeErrors(ef);
    return ef;
  }

  /**
   * Returns normalized 1D alignment errors for 2D images.
   * The number of lags nl = 1+shiftMax-shiftMin. Lag indices 
   * il = 0, 1, 2, ..., nl-1 correspond to integer shifts in 
   * [shiftMin,shiftMax].
   * @param f array[n2][n1] for the image f[i2][i1].
   * @param g array[n2][n1] for the image g[i2][i1].
   * @return array[n1][nl] of alignment errors.
   */
  public float[][] computeErrors1(float[][] f, float[][] g) {
    final float[][] ff = f;
    final float[][] gf = g;
    final int nl = 1+_lmax-_lmin;
    final int n1 = f[0].length;
    final int n2 = f.length;
    float[][] e = Parallel.reduce(n2,new Parallel.ReduceInt<float[][]>() {
    public float[][] compute(int i2) {
      float[][] e = new float[n1][nl];
      computeErrors(ff[i2],gf[i2],e);
      return e;
    }
    public float[][] combine(float[][] ea, float[][] eb) {
      return add(ea,eb);
    }});
    normalizeErrors(e);
    return e;
  }

  /**
   * Returns normalized 1D alignment errors for 3D images.
   * The number of lags nl = 1+shiftMax-shiftMin. Lag indices 
   * il = 0, 1, 2, ..., nl-1 correspond to integer shifts in 
   * [shiftMin,shiftMax].
   * @param f array[n3][n2][n1] for the image f[i3][i2][i1].
   * @param g array[n3][n2][n1] for the image g[i3][i2][i1].
   * @return array[n1][nl] of alignment errors.
   */
  public float[][] computeErrors1(float[][][] f, float[][][] g) {
    final float[][][] ff = f;
    final float[][][] gf = g;
    final int nl = 1+_lmax-_lmin;
    final int n1 = f[0][0].length;
    final int n2 = f[0].length;
    final int n3 = f.length;
    float[][] e = Parallel.reduce(n2*n3,new Parallel.ReduceInt<float[][]>() {
    public float[][] compute(int i23) {
      int i2 = i23%n2;
      int i3 = i23/n2;
      float[][] e = new float[n1][nl];
      computeErrors(ff[i3][i2],gf[i3][i2],e);
      return e;
    }
    public float[][] combine(float[][] ea, float[][] eb) {
      return add(ea,eb);
    }});
    normalizeErrors(e);
    return e;
  }

  /**
   * Returns smoothed (and normalized) alignment errors.
   * @param e array[n1][nl] of alignment errors.
   * @return array[n1][nl] of smoothed errors.
   */
  public float[][] smoothErrors(float[][] e) {
    float[][] es = like(e);
    smoothErrors(e,es);
    return es;
  }

  /**
   * Returns smoothed (and normalized) alignment errors.
   * @param e array[n2][n1][nl] of alignment errors.
   * @return array[n2][n1][nl] of smoothed errors.
   */
  public float[][][] smoothErrors(float[][][] e) {
    float[][][] es = like(e);
    smoothErrors(e,es);
    return es;
  }

  /**
   * Smooths (and normalizes) alignment errors.
   * Input and output arrays can be the same array.
   * @param e input array[n1][nl] of alignment errors.
   * @param es output array[n1][nl] of smoothed errors.
   */
  public void smoothErrors(float[][] e, float[][] es) {
    smoothErrors1(_bstrain1,e,es);
    normalizeErrors(es);
  }

  /**
   * Smooths (and normalizes) alignment errors.
   * Input and output arrays can be the same array.
   * @param e input array[n2][n1][nl] of alignment errors.
   * @param es output array[n2][n1][nl] of smoothed errors.
   */
  public void smoothErrors(float[][][] e, float[][][] es) {
    smoothErrors1(_bstrain1,e,es);
    normalizeErrors(es);
    smoothErrors2(_bstrain2,es,es);
    normalizeErrors(es);
  }

  /**
   * Smooths (and normalizes) alignment errors in only the 1st dimension.
   * Input and output arrays can be the same array.
   * @param e input array[n2][n1][nl] of alignment errors.
   * @param es output array[n2][n1][nl] of smoothed errors.
   */
  public void smoothErrors1(float[][][] e, float[][][] es) {
    smoothErrors1(_bstrain1,e,es);
    normalizeErrors(es);
  }

  /**
   * Returns smoothed shifts.
   * @param u array of shifts to be smoothed.
   * @return array of smoothed shifts
   */
  public float[] smoothShifts(float[] u) {
    float[] us = like(u);
    smoothShifts(u,us);
    return us;
  }

  /**
   * Returns smoothed shifts.
   * @param u array of shifts to be smoothed.
   * @return array of smoothed shifts
   */
  public float[][] smoothShifts(float[][] u) {
    float[][] us = like(u);
    smoothShifts(u,us);
    return us;
  }

  /**
   * Smooths the specified shifts. Smoothing can be performed 
   * in place; input and output arrays can be the same array.
   * @param u input array of shifts to be smoothed.
   * @param us output array of smoothed shifts.
   */
  public void smoothShifts(float[] u, float[] us) {
    if (_ref1!=null) {
      _ref1.apply(u,us); 
    } else if (u!=us) {
      copy(u,us);
    }
  }

  /**
   * Smooths the specified shifts. Smoothing can be performed 
   * in place; input and output arrays can be the same array.
   * @param u input array of shifts to be smoothed.
   * @param us output array of smoothed shifts.
   */
  public void smoothShifts(float[][] u, float[][] us) {
    if (_ref1!=null) {
      _ref1.apply1(u,us);
    } else {
      copy(u,us);
    }
    if (_ref2!=null)
      _ref2.apply2(us,us);
  }

  /**
   * Returns errors accumulated in forward direction.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][] accumulateForward(float[][] e) {
    float[][] d = like(e);
    accumulateForward(e,d);
    return d;
  }

  /**
   * Returns errors accumulated in reverse direction.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][] accumulateReverse(float[][] e) {
    float[][] d = like(e);
    accumulateReverse(e,d);
    return d;
  }

  /**
   * Returns errors accumulated in forward direction in 1st dimension.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][][] accumulateForward1(float[][][] e) {
    float[][][] d = like(e);
    accumulateForward1(e,d);
    return d;
  }

  /**
   * Returns errors accumulated in reverse direction in 1st dimension.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][][] accumulateReverse1(float[][][] e) {
    float[][][] d = like(e);
    accumulateReverse1(e,d);
    return d;
  }

  /**
   * Returns errors accumulated in forward direction in 2nd dimension.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][][] accumulateForward2(float[][][] e) {
    float[][][] d = like(e);
    accumulateForward2(e,d);
    return d;
  }

  /**
   * Returns errors accumulated in reverse direction in 2nd dimension.
   * @param e array of alignment errors.
   * @return array of accumulated errors.
   */
  public float[][][] accumulateReverse2(float[][][] e) {
    float[][][] d = like(e);
    accumulateReverse2(e,d);
    return d;
  }

  /**
   * Accumulates alignment errors in forward direction.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateForward(float[][] e, float[][] d) {
    accumulate( 1,_bstrain1,e,d);
  }

  /**
   * Accumulates alignment errors in reverse direction.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateReverse(float[][] e, float[][] d) {
    accumulate(-1,_bstrain1,e,d);
  }

  /**
   * Accumulates alignment errors in forward direction in 1st dimension.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateForward1(float[][][] e, float[][][] d) {
    int n2 = e.length;
    for (int i2=0; i2<n2; ++i2)
      accumulateForward(e[i2],d[i2]);
  }

  /**
   * Accumulates alignment errors in reverse direction in 1st dimension.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateReverse1(float[][][] e, float[][][] d) {
    int n2 = e.length;
    for (int i2=0; i2<n2; ++i2)
      accumulateReverse(e[i2],d[i2]);
  }

  /**
   * Accumulates alignment errors in forward direction in 2nd dimension.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateForward2(float[][][] e, float[][][] d) {
    int n1 = e[0].length;
    int n2 = e.length;
    float[][]  ei1 = new float[n2][];
    float[][] di1 = new float[n2][];
    for (int i1=0; i1<n1; ++i1) {
      for (int i2=0; i2<n2; ++i2) {
        ei1[i2] = e[i2][i1];
        di1[i2] = d[i2][i1];
      }
      accumulate( 1,_bstrain2,ei1,di1);
    }
  }

  /**
   * Accumulates alignment errors in reverse direction in 2nd dimension.
   * @param e input array of alignment errors.
   * @param d output array of accumulated errors.
   */
  public void accumulateReverse2(float[][][] e, float[][][] d) {
    int n1 = e[0].length;
    int n2 = e.length;
    float[][]  ei1 = new float[n2][];
    float[][] di1 = new float[n2][];
    for (int i1=0; i1<n1; ++i1) {
      for (int i2=0; i2<n2; ++i2) {
        ei1[i2] = e[i2][i1];
        di1[i2] = d[i2][i1];
      }
      accumulate(-1,_bstrain2,ei1,di1);
    }
  }

  /**
   * Returns shifts found by backtracking in reverse.
   * @param d array of accumulated errors.
   * @param e array of alignment errors.
   */
  public float[] backtrackReverse(float[][] d, float[][] e) {
    float[] u = new float[d.length];
    backtrackReverse(d,e,u);
    return u;
  }

  /**
   * Returns shifts found by backtracking in reverse in 1st dimension.
   * @param d array of accumulated errors.
   * @param e array of alignment errors.
   */
  public float[][] backtrackReverse1(float[][][] d, float[][][] e) {
    float[][] u = new float[d.length][d[0].length];
    backtrackReverse1(d,e,u);
    return u;
  }

  /**
   * Returns shifts found by backtracking in reverse in 2nd dimension.
   * @param d array of accumulated errors.
   * @param e array of alignment errors.
   */
  public float[][] backtrackReverse2(float[][][] d, float[][][] e) {
    float[][] u = new float[d.length][d[0].length];
    backtrackReverse2(d,e,u);
    return u;
  }

  /**
   * Computes shifts by backtracking in reverse direction.
   * @param d input array of accumulated errors.
   * @param e input array of alignment errors.
   * @param u output array of shifts.
   */
  public void backtrackReverse(float[][] d, float[][] e, float[] u) {
    backtrack(-1,_bstrain1,_lmin,d,e,u);
  }

  /**
   * Computes shifts by backtracking in reverse direction in 1st dimension.
   * @param d input array of accumulated errors.
   * @param e input array of alignment errors.
   * @param u output array of shifts.
   */
  public void backtrackReverse1(float[][][] d, float[][][] e, float[][] u) {
    int n2 = d.length;
    for (int i2=0; i2<n2; ++i2)
      backtrackReverse(d[i2],e[i2],u[i2]);
  }

  /**
   * Computes shifts by backtracking in reverse direction in 2nd dimension.
   * @param d input array of accumulated errors.
   * @param e input array of alignment errors.
   * @param u output array of shifts.
   */
  public void backtrackReverse2(float[][][] d, float[][][] e, float[][] u) {
    int n1 = d[0].length;
    int n2 = d.length;
    float[][] di1 = new float[n2][];
    float[][] ei1 = new float[n2][];
    float[] ui1 = new float[n2];
    for (int i1=0; i1<n1; ++i1) {
      for (int i2=0; i2<n2; ++i2) {
        di1[i2] = d[i2][i1];
        ei1[i2] = e[i2][i1];
      }
      backtrack(-1,_bstrain2,_lmin,di1,ei1,ui1);
      for (int i2=0; i2<n2; ++i2)
        u[i2][i1] = ui1[i2];
    }
  }

  /**
   * Normalizes alignment errors to be in range [0,1].
   * @param e input/output array of alignment errors.
   */
  public static void normalizeErrors(float[][] e) {
    int nl = e[0].length;
    int n1 = e.length;
    float emin = e[0][0];
    float emax = e[0][0];
    for (int i1=0; i1<n1; ++i1) {
      for (int il=0; il<nl; ++il) {
        float ei = e[i1][il];
        if (ei<emin) emin = ei;
        if (ei>emax) emax = ei;
      }
    }
    shiftAndScale(emin,emax,e);
  }

  /**
   * Normalizes alignment errors to be in range [0,1].
   * @param e input/output array of alignment errors.
   */
  public static void normalizeErrors(float[][][] e) {
    final float[][][] ef = e;
    int n2 = e.length;
    MinMax mm = Parallel.reduce(n2,new Parallel.ReduceInt<MinMax>() {
    public MinMax compute(int i2) {
      int nl = ef[i2][0].length;
      int n1 = ef[i2].length;
      float emin =  Float.MAX_VALUE;
      float emax = -Float.MAX_VALUE;
      for (int i1=0; i1<n1; ++i1) {
        for (int il=0; il<nl; ++il) {
          float ei = ef[i2][i1][il];
          if (ei<emin) emin = ei;
          if (ei>emax) emax = ei;
        }
      }
      return new MinMax(emin,emax);
    }
    public MinMax combine(MinMax mm1, MinMax mm2) {
      return new MinMax(min(mm1.emin,mm2.emin),max(mm1.emax,mm2.emax));
    }});
    shiftAndScale(mm.emin,mm.emax,e);
  }

  /**
   * Returns the sum of errors for specified shifts, rounded to integers.
   * @param e array[n1][nl] of errors.
   * @param u array[n1] of shifts.
   * @return the sum of errors.
   */
  public float sumErrors(float[][] e, float[] u) {
    int n1 = e.length;
    int nl = e[0].length;
    float ul = 0.5f-_lmin;
    double sum = 0.0;
    for (int i1=0; i1<n1; ++i1) {
      int il = (int)(u[i1]+ul);
      il = max(0,min(nl-1,il));
      sum += e[i1][il];
    }
    return (float)sum;
  }

  /**
   * Returns the sum of errors for specified shifts, rounded to integers.
   * @param e array[n2][n1][nl] of errors.
   * @param u array[n2][n1] of shifts.
   * @return the sum of errors.
   */
  public float sumErrors(float[][][] e, float[][] u) {
    int n2 = e.length;
    double sum = 0.0;
    for (int i2=0; i2<n2; ++i2)
      sum += sumErrors(e[i2],u[i2]);
    return (float)sum;
  }

  /**
   * Returns errors in an array with lag the slowest dimension.
   * Useful only for visualization of errors. Other methods in this
   * class assume that lag is the fastest dimension in arrays of errors.
   * @param e array[n1][nl] of errors.
   * @return transposed array[nl][n1] of errors.
   */
  public static float[][] transposeLag(float[][] e) {
    int nl = e[0].length;
    int n1 = e.length;
    float[][] t = new float[nl][n1];
    for (int il=0; il<nl; ++il) {
      for (int i1=0; i1<n1; ++i1) {
        t[il][i1] = e[i1][il];
      }
    }
    return t;
  }

  /**
   * Returns errors in an array with lag the slowest dimension.
   * Useful only for visualization of errors. Other methods in this
   * class assume that lag is the fastest dimension in arrays of errors.
   * @param e array[n2][n1][nl] of errors.
   * @return transposed array[nl][n2][n1] of errors.
   */
  public static float[][][] transposeLag(float[][][] e) {
    int nl = e[0][0].length;
    int n1 = e[0].length;
    int n2 = e.length;
    float[][][] t = new float[nl][n2][n1];
    for (int il=0; il<nl; ++il) {
      for (int i2=0; i2<n2; ++i2) {
        for (int i1=0; i1<n1; ++i1) {
          t[il][i2][i1] = e[i2][i1][il];
        }
      }
    }
    return t;
  }

  ///////////////////////////////////////////////////////////////////////////
  // private

  private int _nl; // number of lags
  private int _lmin,_lmax; // min,max lags
  private int _dw=2;
  private int _wind=100;
  private int _ud=2;
  private ErrorExtrapolation _extrap; // method for error extrapolation
  private float _epow = 2; // exponent used for alignment errors |f-g|^e
  private int _esmooth = 0; // number of nonlinear smoothings of errors
  private double _usmooth1 = 0.0; // extent of smoothing shifts in 1st dim
  private double _usmooth2 = 0.0; // extent of smoothing shifts in 2nd dim
  private double _usmooth3 = 0.0; // extent of smoothing shifts in 3rd dim
  private int _bstrain1 = 1; // inverse of bound on strain in 1st dimension
  private int _bstrain2 = 1; // inverse of bound on strain in 2nd dimension
  private int _bstrain3 = 1; // inverse of bound on strain in 3rd dimension
  private RecursiveExponentialFilter _ref1; // for smoothing shifts
  private RecursiveExponentialFilter _ref2; // for smoothing shifts
  private RecursiveExponentialFilter _ref3; // for smoothing shifts
  private SincInterpolator _si; // for warping with non-integer shifts
  private int _owl2 = 50; // window size in 2nd dimension for 3D images
  private int _owl3 = 50; // window size in 3rd dimension for 3D images
  private double _owf2 = 0.5; // fraction of window overlap in 2nd dimension
  private double _owf3 = 0.5; // fraction of window overlap in 3rd dimension

  private float error(float f, float g) {
    if(_epow==1f) return abs(f-g);
    else return pow(abs(f-g),_epow);
  }

  private void updateSmoothingFilters() {
    _ref1 = (_usmooth1<=0.0) ? null :
      new RecursiveExponentialFilter(_usmooth1*_bstrain1);
    _ref2 = (_usmooth2<=0.0) ? null :
      new RecursiveExponentialFilter(_usmooth2*_bstrain2);
    _ref3 = (_usmooth3<=0.0) ? null :
      new RecursiveExponentialFilter(_usmooth3*_bstrain3);
  }

  private void computeErrorsReverse(
    int m2, float[] u, float[] el, 
    float[] sm, float[][] f, float[] g, float[][] e) {
    int n2 = f.length;
    int n1 = g.length;
    int nl = _nl;
    int n1m = n1-1;
    boolean average = _extrap==ErrorExtrapolation.AVERAGE;
    boolean nearest = _extrap==ErrorExtrapolation.NEAREST;
    boolean reflect = _extrap==ErrorExtrapolation.REFLECT;
    float[] eavg = average?new float[nl]:null; 
    int[] navg = average?new int[nl]:null;
    float emax = 0.0f;

    // Notes for indexing:
    // 0 <= il < nl, where il is index for lag
    // 0 <= i1 < n1, where i1 is index for sequence f
    // 0 <= j1 < n1, where j1 is index for sequence g
    // j1 = i1+il+lmin, where il+lmin = lag
    // 0 <= i1+il+lmin < n1, so that j1 is in bounds
    // max(0,-lmin-i1) <= il < min(nl,n1-lmin-i1)
    // max(0,-lmin-il) <= i1 < min(n1,n1-lmin-il)
    // j1 = 0    => i1 =     -lmin-il
    // j1 = n1-1 => i1 = n1-1-lmin-il

    // Compute errors where indices are in bounds for both f and g.
    int d2 = _dw;
    int i2b = min(n2-1,m2+_wind*d2);
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // see notes
      int ilhi = min(nl,n1-_lmin-i1); // above
      int ui = round(u[i1]);
      int ud = _ud;
      if(el[i1]>0.8f) ud = 1;
      int lb = ui-ud-_lmin;
      int le = ui+ud-_lmin+1;
      illo = max(illo,lb);
      ilhi = min(ilhi,le);
      for (int il=illo,j1=i1+il+_lmin; il<ilhi; ++il,++j1) {
        float ei = error(f[0][i1],g[j1]);
        float sc = 1f;
        for (int i2=i2b; i2<m2; i2-=d2) {
          if(sm[i2]>0.5f) {
            ei += error(f[i2][i1],g[j1]);
            sc += 1f;
          }
        }
        ei /= sc;
        e[i1][il] = ei;
        if (average) {
          eavg[il] += ei;
          navg[il] += 1;
        }
        if (ei>emax) 
          emax = ei;
      }
    }

    // If necessary, complete computation of average errors for each lag.
    if (average) {
      for (int il=0; il<nl; ++il) {
        if (navg[il]>0)
          eavg[il] /= navg[il];
      }
    }

    // For indices where errors have not yet been computed, extrapolate.
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // same as
      int ilhi = min(nl,n1-_lmin-i1); // above
      for (int il=0; il<nl; ++il) {
        if (il<illo || il>=ilhi) {
          if (average) {
            if (navg[il]>0) {
              e[i1][il] = eavg[il];
            } else {
              e[i1][il] = emax;
            }
          } else if (nearest || reflect) {
            int k1 = (il<illo)?-_lmin-il:n1m-_lmin-il;
            if (reflect)
              k1 += k1-i1;
            if (0<=k1 && k1<n1) {
              e[i1][il] = e[k1][il];
            } else {
              e[i1][il] = emax;
            }
          } else {
            e[i1][il] = emax;
          }
        }
      }
    }
  }


  /**
   * Computes alignment errors, not normalized.
   * @param f input array[ni] for sequence f.
   * @param g input array[ni] for sequence g.
   * @param e output array[ni][nl] of alignment errors.
   */
  private void computeErrors(
    int m2, float[] u, float[] el, 
    float[] sm, float[][] f, float[] g, float[][] e) {
    int n1 = g.length;
    int nl = _nl;
    int n1m = n1-1;
    boolean average = _extrap==ErrorExtrapolation.AVERAGE;
    boolean nearest = _extrap==ErrorExtrapolation.NEAREST;
    boolean reflect = _extrap==ErrorExtrapolation.REFLECT;
    float[] eavg = average?new float[nl]:null; 
    int[] navg = average?new int[nl]:null;
    float emax = 0.0f;

    // Notes for indexing:
    // 0 <= il < nl, where il is index for lag
    // 0 <= i1 < n1, where i1 is index for sequence f
    // 0 <= j1 < n1, where j1 is index for sequence g
    // j1 = i1+il+lmin, where il+lmin = lag
    // 0 <= i1+il+lmin < n1, so that j1 is in bounds
    // max(0,-lmin-i1) <= il < min(nl,n1-lmin-i1)
    // max(0,-lmin-il) <= i1 < min(n1,n1-lmin-il)
    // j1 = 0    => i1 =     -lmin-il
    // j1 = n1-1 => i1 = n1-1-lmin-il

    // Compute errors where indices are in bounds for both f and g.
    int d2 = _dw;
    int i2b = max(0,m2-_wind*d2);
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // see notes
      int ilhi = min(nl,n1-_lmin-i1); // above
      int ui = round(u[i1]);
      int ud = _ud;
      if(el[i1]>0.8f) ud = 1;
      int lb = ui-ud-_lmin;
      int le = ui+ud-_lmin+1;
      illo = max(illo,lb);
      ilhi = min(ilhi,le);
      for (int il=illo,j1=i1+il+_lmin; il<ilhi; ++il,++j1) {
        float ei = error(f[0][i1],g[j1]);
        float sc = 1f;
        for (int i2=i2b; i2<m2; i2+=d2) {
          if(sm[i2]>0.5f) {
            ei += error(f[i2][i1],g[j1]);
            sc += 1f;
          }
        }
        ei /= sc;
        e[i1][il] = ei;
        if (average) {
          eavg[il] += ei;
          navg[il] += 1;
        }
        if (ei>emax) 
          emax = ei;
      }
    }

    // If necessary, complete computation of average errors for each lag.
    if (average) {
      for (int il=0; il<nl; ++il) {
        if (navg[il]>0)
          eavg[il] /= navg[il];
      }
    }

    // For indices where errors have not yet been computed, extrapolate.
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // same as
      int ilhi = min(nl,n1-_lmin-i1); // above
      for (int il=0; il<nl; ++il) {
        if (il<illo || il>=ilhi) {
          if (average) {
            if (navg[il]>0) {
              e[i1][il] = eavg[il];
            } else {
              e[i1][il] = emax;
            }
          } else if (nearest || reflect) {
            int k1 = (il<illo)?-_lmin-il:n1m-_lmin-il;
            if (reflect)
              k1 += k1-i1;
            if (0<=k1 && k1<n1) {
              e[i1][il] = e[k1][il];
            } else {
              e[i1][il] = emax;
            }
          } else {
            e[i1][il] = emax;
          }
        }
      }
    }
  }

  private float correlate(float[] f, float[] g) {
    int n1 = f.length;
    float ff = 0f;
    float gg = 0f;
    float fg = 0f;
    for (int i1=0; i1<n1; ++i1) {
      float fi = f[i1];
      float gi = g[i1];
      fg += fi*gi;
      ff += fi*fi;
      gg += gi*gi;
    }
    return fg*fg/(ff*gg);
  }

  
  private float[][] correlate(float sig, float[] f, float[] g) {
    int n1 = f.length;
    LocalCorrelationFilter.Type tp = LocalCorrelationFilter.Type.SIMPLE;
    LocalCorrelationFilter.Window wd = LocalCorrelationFilter.Window.GAUSSIAN;
    LocalCorrelationFilter lcf = new LocalCorrelationFilter(tp,wd,sig);
    lcf.setInputs(f,g);
    float[][] cs = new float[_nl][n1];
    for (int il=0; il<_nl; ++il) {
      float[] cl = cs[il];
      lcf.correlate(il+_lmin,cl);
      lcf.normalize(il+_lmin,cl);
      for (int i1=0; i1<n1; ++i1)
        cl[i1] = 1f-abs(cl[i1]);
    }
    return cs;
  }
  

  /**
   * Computes alignment errors, not normalized.
   * @param f input array[ni] for sequence f.
   * @param g input array[ni] for sequence g.
   * @param e output array[ni][nl] of alignment errors.
   */
  private void computeErrors(float[] f, float[] g, float[][] e) {
    int n1 = f.length;
    int nl = _nl;
    int n1m = n1-1;
    boolean average = _extrap==ErrorExtrapolation.AVERAGE;
    boolean nearest = _extrap==ErrorExtrapolation.NEAREST;
    boolean reflect = _extrap==ErrorExtrapolation.REFLECT;
    float[] eavg = average?new float[nl]:null; 
    int[] navg = average?new int[nl]:null;
    float emax = 0.0f;

    // Notes for indexing:
    // 0 <= il < nl, where il is index for lag
    // 0 <= i1 < n1, where i1 is index for sequence f
    // 0 <= j1 < n1, where j1 is index for sequence g
    // j1 = i1+il+lmin, where il+lmin = lag
    // 0 <= i1+il+lmin < n1, so that j1 is in bounds
    // max(0,-lmin-i1) <= il < min(nl,n1-lmin-i1)
    // max(0,-lmin-il) <= i1 < min(n1,n1-lmin-il)
    // j1 = 0    => i1 =     -lmin-il
    // j1 = n1-1 => i1 = n1-1-lmin-il

    // Compute errors where indices are in bounds for both f and g.
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // see notes
      int ilhi = min(nl,n1-_lmin-i1); // above
      for (int il=illo,j1=i1+il+_lmin; il<ilhi; ++il,++j1) {
        float ei = error(f[i1],g[j1]);
        e[i1][il] = ei;
        if (average) {
          eavg[il] += ei;
          navg[il] += 1;
        }
        if (ei>emax) 
          emax = ei;
      }
    }

    // If necessary, complete computation of average errors for each lag.
    if (average) {
      for (int il=0; il<nl; ++il) {
        if (navg[il]>0)
          eavg[il] /= navg[il];
      }
    }

    // For indices where errors have not yet been computed, extrapolate.
    for (int i1=0; i1<n1; ++i1) {
      int illo = max(0,   -_lmin-i1); // same as
      int ilhi = min(nl,n1-_lmin-i1); // above
      for (int il=0; il<nl; ++il) {
        if (il<illo || il>=ilhi) {
          if (average) {
            if (navg[il]>0) {
              e[i1][il] = eavg[il];
            } else {
              e[i1][il] = emax;
            }
          } else if (nearest || reflect) {
            int k1 = (il<illo)?-_lmin-il:n1m-_lmin-il;
            if (reflect)
              k1 += k1-i1;
            if (0<=k1 && k1<n1) {
              e[i1][il] = e[k1][il];
            } else {
              e[i1][il] = emax;
            }
          } else {
            e[i1][il] = emax;
          }
        }
      }
    }
  }

  /**
   * Non-linear accumulation of alignment errors.
   * @param dir accumulation direction, positive or negative.
   * @param b sample offset used to constrain changes in lag.
   * @param e input array[ni][nl] of alignment errors.
   * @param d output array[ni][nl] of accumulated errors.
   */
  private void accumulateX(int dir, float[] w, 
    float[][] e, float[][] d) {
    int nl = e[0].length;
    int ni = e.length;
    int nlm1 = nl-1;
    int nim1 = ni-1;
    int ib = (dir>0)?0:nim1;
    int ie = (dir>0)?ni:-1;
    int is = (dir>0)?1:-1;
    for (int il=0; il<nl; ++il)
      d[ib][il] = 0.0f;
    for (int ii=ib; ii!=ie; ii+=is) {
      float wi = w[ii];
      int b = _ud;
      if(wi>0.8f) b = 1;
      int ji = max(0,min(nim1,ii-is));
      for (int il=0; il<nl; ++il) {
        float dmin = FLT_MAX;
        for (int k=-b; k<=b; ++k) {
          int ilk = il+k;
          ilk = max(ilk,0);
          ilk = min(ilk,nlm1);
          float dk = d[ji][ilk];
          if(dk<dmin) dmin = dk;
        }
        d[ii][il] = dmin+e[ii][il];
      }
    }
  }


  /**
   * Non-linear accumulation of alignment errors.
   * @param dir accumulation direction, positive or negative.
   * @param b sample offset used to constrain changes in lag.
   * @param e input array[ni][nl] of alignment errors.
   * @param d output array[ni][nl] of accumulated errors.
   */
  private static void accumulate(int dir, int b, float[][] e, float[][] d) {
    int nl = e[0].length;
    int ni = e.length;
    int nlm1 = nl-1;
    int nim1 = ni-1;
    int ib = (dir>0)?0:nim1;
    int ie = (dir>0)?ni:-1;
    int is = (dir>0)?1:-1;
    for (int il=0; il<nl; ++il)
      d[ib][il] = 0.0f;
    for (int ii=ib; ii!=ie; ii+=is) {
      int ji = max(0,min(nim1,ii-is));
      int jb = max(0,min(nim1,ii-is*b));
      for (int il=0; il<nl; ++il) {
        int ilm1 = il-1; if (ilm1==-1) ilm1 = 0;
        int ilp1 = il+1; if (ilp1==nl) ilp1 = nlm1;
        float dm = d[jb][ilm1];
        float di = d[ji][il  ];
        float dp = d[jb][ilp1];
        for (int kb=ji; kb!=jb; kb-=is) {
          dm += e[kb][ilm1];
          dp += e[kb][ilp1];
        }
        d[ii][il] = min3(dm,di,dp)+e[ii][il];
      }
    }
  }

  /**
   * Finds shifts by backtracking in accumulated alignment errors.
   * Backtracking must be performed in the direction opposite to
   * that for which accumulation was performed.
   * @param dir backtrack direction, positive or negative.
   * @param b sample offset used to constrain changes in lag.
   * @param lmin minimum lag corresponding to lag index zero.
   * @param d input array[ni][nl] of accumulated errors.
   * @param e input array[ni][nl] of alignment errors.
   * @param u output array[ni] of computed shifts.
   */
  private static void backtrack(
    int dir, int b, int lmin, float[][] d, float[][] e, float[] u) 
  {
    float ob = 1.0f/b;
    int nl = d[0].length;
    int ni = d.length;
    int nlm1 = nl-1;
    int nim1 = ni-1;
    int ib = (dir>0)?0:nim1;
    int ie = (dir>0)?nim1:0;
    int is = (dir>0)?1:-1;
    int ii = ib;
    int il = max(0,min(nlm1,-lmin));
    float dl = d[ii][il];
    for (int jl=1; jl<nl; ++jl) {
      if (d[ii][jl]<dl) {
        dl = d[ii][jl];
        il = jl;
      }
    }
    u[ii] = il+lmin;
    while (ii!=ie) {
      int ji = max(0,min(nim1,ii+is));
      int jb = max(0,min(nim1,ii+is*b));
      int ilm1 = il-1; if (ilm1==-1) ilm1 = 0;
      int ilp1 = il+1; if (ilp1==nl) ilp1 = nlm1;
      float dm = d[jb][ilm1];
      float di = d[ji][il  ];
      float dp = d[jb][ilp1];
      for (int kb=ji; kb!=jb; kb+=is) {
        dm += e[kb][ilm1];
        dp += e[kb][ilp1];
      }
      dl = min3(dm,di,dp);
      if (dl!=di) {
        if (dl==dm) {
          il = ilm1;
        } else {
          il = ilp1;
        }
      }
      ii += is;
      u[ii] = il+lmin;
      if (il==ilm1 || il==ilp1) {
        float du = (u[ii]-u[ii-is])*ob;
        u[ii] = u[ii-is]+du;
        for (int kb=ji; kb!=jb; kb+=is) {
          ii += is;
          u[ii] = u[ii-is]+du;
        }
      }
    }
  }

  /**
   * Shifts and scales alignment errors to be in range [0,1].
   * @param emin minimum alignment error before normalizing.
   * @param emax maximum alignment error before normalizing.
   * @param e input/output array of alignment errors.
   */
  private static void shiftAndScale(float emin, float emax, float[][] e) {
    int nl = e[0].length;
    int n1 = e.length;
    float eshift = emin;
    float escale = (emax>emin)?1.0f/(emax-emin):1.0f;
    for (int i1=0; i1<n1; ++i1) {
      for (int il=0; il<nl; ++il) {
        e[i1][il] = (e[i1][il]-eshift)*escale;
      }
    }
  }

  /**
   * Shifts and scales alignment errors to be in range [0,1].
   * @param emin minimum alignment error before normalizing.
   * @param emax maximum alignment error before normalizing.
   * @param e input/output array of alignment errors.
   */
  private static void shiftAndScale(float emin, float emax, float[][][] e) {
    final int n2 = e.length;
    final float eshift = emin;
    final float escale = (emax>emin)?1.0f/(emax-emin):1.0f;
    final float[][][] ef = e;
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      int nl = ef[i2][0].length;
      int n1 = ef[i2].length;
      for (int i1=0; i1<n1; ++i1) {
        for (int il=0; il<nl; ++il) {
          ef[i2][i1][il] = (ef[i2][i1][il]-eshift)*escale;
        }
      }
    }});
  }

  /**
   * Smooths alignment errors in 1st dimension.
   * Does not normalize errors after smoothing.
   * @param b strain parameter in 1st dimension.
   * @param e input array of alignment errors to be smooothed.
   * @param es output array of smoothed alignment errors.
   */
  private static void smoothErrors1(int b, float[][] e, float[][] es) {
    int nl = e[0].length;
    int n1 = e.length;
    float[][] ef = new float[n1][nl];
    float[][] er = new float[n1][nl];
    accumulate( 1,b,e,ef);
    accumulate(-1,b,e,er);
    for (int i1=0; i1<n1; ++i1)
      for (int il=0; il<nl; ++il)
        es[i1][il] = ef[i1][il]+er[i1][il]-e[i1][il];
  }

  /**
   * Smooths alignment errors in 1st dimension.
   * Does not normalize errors after smoothing.
   * @param b strain parameter in 1st dimension.
   * @param e input array of alignment errors to be smooothed.
   * @param es output array of smoothed alignment errors.
   */
  private static void smoothErrors1(int b, float[][][] e, float[][][] es) {
    final int n2 = e.length;
    final int bf = b;
    final float[][][] ef = e;
    final float[][][] esf = es;
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      smoothErrors1(bf,ef[i2],esf[i2]);
    }});
  }

  /**
   * Smooths alignment errors in 2nd dimension.
   * Does not normalize errors after smoothing.
   * @param b strain parameter in 2nd dimension.
   * @param e input array of alignment errors to be smooothed.
   * @param es output array of smoothed alignment errors.
   */
  private static void smoothErrors2(int b, float[][][] e, float[][][] es) {
    final int nl = e[0][0].length;
    final int n1 = e[0].length;
    final int n2 = e.length;
    final int bf = b;
    final float[][][]  ef = e;
    final float[][][] esf = es;
    final Parallel.Unsafe<float[][][]> eeu = 
      new Parallel.Unsafe<float[][][]>();
    Parallel.loop(n1,new Parallel.LoopInt() {
    public void compute(int i1) {
      float[][][] ee = eeu.get();
      if (ee==null) eeu.set(ee=new float[4][n2][nl]);
      float[][]  e1 = ee[0];
      float[][] es1 = ee[1];
      float[][] ef1 = ee[2];
      float[][] er1 = ee[3];
      for (int i2=0; i2<n2; ++i2) {
         e1[i2] =  ef[i2][i1];
        es1[i2] = esf[i2][i1];
        for (int il=0; il<nl; ++il) {
          ef1[i2][il] = 0.0f;
          er1[i2][il] = 0.0f;
        }
      }
      accumulate( 1,bf,e1,ef1);
      accumulate(-1,bf,e1,er1);
      for (int i2=0; i2<n2; ++i2) {
        for (int il=0; il<nl; ++il) {
          es1[i2][il] = ef1[i2][il]+er1[i2][il]-e1[i2][il];
        }
      }
    }});
  }

  private static float min3(float a, float b, float c) {
    return b<=a?(b<=c?b:c):(a<=c?a:c); // if equal, choose b
  }

  private static float[] like(float[] a) {
    return new float[a.length];
  }
  private static float[][] like(float[][] a) {
    return new float[a.length][a[0].length];
  }
  private static float[][][] like(float[][][] a) {
    return new float[a.length][a[0].length][a[0][0].length];
  }

  ///////////////////////////////////////////////////////////////////////////
  // for 3D image warping

  private void computeErrors(float[][][] f, float[][][] g, float[][][][] e) {
    final int n2 = e[0].length;
    final int n3 = e.length;
    final float[][][] ff = f;
    final float[][][] gf = g;
    final float[][][][] ef = e;
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2) {
        computeErrors(ff[i3][i2],gf[i3][i2],ef[i3][i2]);
      }
    }});
    normalizeErrors(e);
  }
  private static void normalizeErrors(float[][][][] e) {
    final int nl = e[0][0][0].length;
    final int n1 = e[0][0].length;
    final int n2 = e[0].length;
    final int n3 = e.length;
    final float[][][][] ef = e;
    MinMax mm = Parallel.reduce(n3,new Parallel.ReduceInt<MinMax>() {
    public MinMax compute(int i3) {
      float emin =  Float.MAX_VALUE;
      float emax = -Float.MAX_VALUE;
      for (int i2=0; i2<n2; ++i2) {
        for (int i1=0; i1<n1; ++i1) {
          for (int il=0; il<nl; ++il) {
            float ei = ef[i3][i2][i1][il];
            if (ei<emin) emin = ei;
            if (ei>emax) emax = ei;
          }
        }
      }
      return new MinMax(emin,emax);
    }
    public MinMax combine(MinMax mm1, MinMax mm2) {
      return new MinMax(min(mm1.emin,mm2.emin),max(mm1.emax,mm2.emax));
    }});
    shiftAndScale(mm.emin,mm.emax,e);
  }
  private static void shiftAndScale(float emin, float emax, float[][][][] e) {
    final int nl = e[0][0][0].length;
    final int n1 = e[0][0].length;
    final int n2 = e[0].length;
    final int n3 = e.length;
    final float eshift = emin;
    final float escale = (emax>emin)?1.0f/(emax-emin):1.0f;
    final float[][][][] ef = e;
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      for (int i2=0; i2<n2; ++i2) {
        for (int i1=0; i1<n1; ++i1) {
          for (int il=0; il<nl; ++il) {
            ef[i3][i2][i1][il] = (ef[i3][i2][i1][il]-eshift)*escale;
          }
        }
      }
    }});
  }
  private void smoothErrors(float[][][][] e) {
    final int n2 = e[0].length;
    final int n3 = e.length;
    final float[][][][] ef = e;
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      smoothErrors1(_bstrain1,ef[i3],ef[i3]);
    }});
    normalizeErrors(e);
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      smoothErrors2(_bstrain2,ef[i3],ef[i3]);
    }});
    normalizeErrors(e);
    Parallel.loop(n2,new Parallel.LoopInt() {
    public void compute(int i2) {
      float[][][] ei2 = new float[n3][][];
      for (int i3=0; i3<n3; ++i3)
        ei2[i3] = ef[i3][i2];
      smoothErrors2(_bstrain3,ei2,ei2);
    }});
    normalizeErrors(e);
  }
  private void computeShifts(float[][][][] e, float[][][] u) {
    final int nl = e[0][0][0].length;
    final int n1 = e[0][0].length;
    final int n2 = e[0].length;
    final int n3 = e.length;
    final float[][][][] ef = e;
    final float[][][] uf = u;
    final Parallel.Unsafe<float[][]> du = new Parallel.Unsafe<float[][]>();
    Parallel.loop(n3,new Parallel.LoopInt() {
    public void compute(int i3) {
      float[][] d = du.get();
      if (d==null) du.set(d=new float[n1][nl]);
      for (int i2=0; i2<n2; ++i2) {
        accumulateForward(ef[i3][i2],d);
        backtrackReverse(d,ef[i3][i2],uf[i3][i2]);
      }
    }});
  }
  private void smoothShifts(float[][][] u) {
    if (_ref1!=null) _ref1.apply1(u,u);
    if (_ref2!=null) _ref2.apply2(u,u);
    if (_ref3!=null) _ref3.apply3(u,u);
  }
  private static class MinMax {
    float emin,emax;
    MinMax(float emin, float emax) {
      this.emin = emin;
      this.emax = emax;
    }
  }
  private static class OverlappingWindows2 {
    public OverlappingWindows2(
      int n1, int n2, int l1, int l2, double f1, double f2) 
    {
      Check.argument(0.0<=f1 && f1<1.0,"0 <= f1 < 1");
      Check.argument(0.0<=f2 && f2<1.0,"0 <= f2 < 1");
      _n1 = n1;
      _n2 = n2;
      _l1 = min(l1,n1);
      _l2 = min(l2,n2);
      _m1 = 1+(int)ceil((_n1-_l1)/(_l1*(1.0-f1)));
      _m2 = 1+(int)ceil((_n2-_l2)/(_l2*(1.0-f2)));
      _s1 = (double)(_n1-_l1)/max(1,_m1-1);
      _s2 = (double)(_n2-_l2)/max(1,_m2-1);
      makeWeights();
      makeScalars();
    }
    public int getL1() { return _l1; }
    public int getL2() { return _l2; }
    public int getM1() { return _m1; }
    public int getM2() { return _m2; }
    public int getI1(int k1) { return (int)(k1*_s1+0.5); }
    public int getI2(int k2) { return (int)(k2*_s2+0.5); }
    public float getWeight(int i1, int i2, int j1, int j2) {
      return _w[j2][j1]*_s[i2+j2][i1+j1];
    }
    private int _n1,_n2; // numbers of samples
    private int _l1,_l2; // window lengths
    private int _m1,_m2; // numbers of windows
    private double _s1,_s2; // nominal window spacings
    private float[][] _w; // weights[l2][l1] for windowing
    private float[][] _s; // scalars[n2][n1] for normalization
    private void makeWeights() {
      _w = new float[_l2][_l1];
      for (int i2=0; i2<_l2; ++i2) {
        for (int i1=0; i1<_l1; ++i1) {
          double s1 = sin((i1+1.0)*PI/(_l1+1.0));
          double s2 = sin((i2+1.0)*PI/(_l2+1.0));
          _w[i2][i1] = (float)(s1*s1*s2*s2);
        }
      }
    }
    private void makeScalars() {
      _s = new float[_n2][_n1];
      for (int k2=0; k2<_m2; ++k2) {
        int i2 = getI2(k2);
        for (int k1=0; k1<_m1; ++k1) {
          int i1 = getI1(k1);
          for (int j2=0; j2<_l2; ++j2) {
            for (int j1=0; j1<_l1; ++j1) {
              _s[i2+j2][i1+j1] += _w[j2][j1];
            }
          }
        }
      }
      for (int i2=0; i2<_n2; ++i2) {
        for (int i1=0; i1<_n1; ++i1) {
          _s[i2][i1] = 1.0f/_s[i2][i1];
        }
      }
    }
  }

  private class Point {
    public int i2;
    public int i3;
    public float w;

    public Point (int i2, int i3, float w) {
      set(i2,i3,w);
    }

    public void set(int i2, int i3, float w) {
      this.i2 = i2;
      this.i3 = i3;
      this.w = w;
    }
  }
}
