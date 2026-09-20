/* Focused follow-up: (a) is the pinned floor stable under a much longer settle?
 * (b) does the NSoff-minus-NSon difference really cross zero above -45 dBFS in? */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "humla_apm.h"
#define SR 48000
#define N 480
static uint64_t rng_s = 0x2026091900000007ull;
static double urand(void) {
    rng_s ^= rng_s << 13; rng_s ^= rng_s >> 7; rng_s ^= rng_s << 17;
    return (double)((int64_t)(rng_s >> 11)) / (double)(1ll << 52) - 1.0;
}
typedef struct { double lp; double ph; } gs;
static void lowpass(gs *g, double *o) { for (int i=0;i<N;i++){ g->lp=0.97*g->lp+0.03*urand(); o[i]=g->lp*8.0; } }
static void white(gs *g, double *o) { (void)g; for (int i=0;i<N;i++) o[i]=urand(); }
static void pinkish(gs *g, double *o) { /* a third character: gentler low-pass */
    for (int i=0;i<N;i++){ g->lp=0.80*g->lp+0.20*urand(); o[i]=g->lp*2.2; } }
typedef void (*genfn)(gs*,double*);
static double rms_of(const double*x,int n){double s=0;for(int i=0;i<n;i++)s+=x[i]*x[i];return sqrt(s/n);}

static double run(genfn gen,double tgt,int ns,int settle,int measure,double *in_dbfs){
    humla_apm_config c; memset(&c,0,sizeof c);
    c.echo_cancellation=1; c.noise_suppression=ns; c.noise_suppression_level=2;
    c.gain_control=1; c.high_pass=1;
    humla_apm *h=humla_apm_create(SR,&c); if(!h){fprintf(stderr,"create fail\n");exit(1);}
    gs g; memset(&g,0,sizeof g); rng_s=0x2026091900000007ull;
    gs gc; memset(&gc,0,sizeof gc); uint64_t sv=rng_s; double b[N],acc=0;
    for(int f=0;f<50;f++){gen(&gc,b);acc+=rms_of(b,N);} double nat=acc/50; rng_s=sv;
    double scale=(32768.0*pow(10.0,tgt/20.0))/(nat*32768.0);
    int16_t fr[N],rn[N]; double ls=0;int ln=0,inn=0;double is=0;
    for(int f=0;f<settle+measure;f++){
        gen(&g,b); double ia=0;
        for(int i=0;i<N;i++){double v=b[i]*32768.0*scale; if(v>32767)v=32767; if(v<-32768)v=-32768;
            fr[i]=(int16_t)lrint(v); ia+=(double)fr[i]*(double)fr[i];}
        if(f>=settle){double r=sqrt(ia/N); if(r>=1.0){is+=20.0*log10(r/32768.0);inn++;}}
        memset(rn,0,sizeof rn); humla_apm_process_render(h,rn);
        if(humla_apm_process_capture(h,fr)!=0){fprintf(stderr,"proc fail\n");exit(1);}
        if(f>=settle){ls+=humla_apm_last_capture_level_dbfs(h);ln++;}
    }
    humla_apm_destroy(h);
    if(in_dbfs)*in_dbfs=inn?is/inn:-100;
    return ls/ln;
}
int main(void){
    printf("=== A. floor stability vs settling time (lowpass noise, NS off) ===\n");
    printf("%8s %10s %10s %10s\n","in","settle500","settle1500","settle3000");
    double lv[]={-60,-55,-50};
    for(size_t i=0;i<3;i++){
        double a=run(lowpass,lv[i],0,500,800,NULL);
        double b2=run(lowpass,lv[i],0,1500,800,NULL);
        double c2=run(lowpass,lv[i],0,3000,800,NULL);
        printf("%8.0f %10.2f %10.2f %10.2f\n",lv[i],a,b2,c2);
    }
    printf("\n=== B. does the difference cross zero above -45 dBFS in? (settle 1500) ===\n");
    printf("%-10s %8s %10s %10s %10s\n","character","in","NSoff","NSon","diff");
    struct{const char*n;genfn f;} cs[]={{"lowpass",lowpass},{"white",white},{"pinkish",pinkish}};
    double lv2[]={-45,-42,-40,-38,-35,-32,-30,-25,-20};
    for(size_t c=0;c<3;c++){
        double minv=1e9; double minat=0;
        for(size_t i=0;i<sizeof lv2/sizeof lv2[0];i++){
            double in; double off=run(cs[c].f,lv2[i],0,1500,800,&in);
            double on=run(cs[c].f,lv2[i],1,1500,800,NULL);
            printf("%-10s %8.2f %10.2f %10.2f %+10.2f\n",cs[c].n,in,off,on,off-on);
            if(off-on<minv){minv=off-on;minat=in;}
        }
        printf("  -> minimum difference for %s: %+.2f dB at %.1f dBFS in  (%s)\n\n",
               cs[c].n,minv,minat, minv<0?"CROSSES ZERO":"never negative");
    }
    return 0;
}
