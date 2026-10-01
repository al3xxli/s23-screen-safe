package ca.screensafe.core;

/** Coordinates in the rotated, full physical display. The fault stays at the natural top. */
public final class SafeArea {
    public static final int WIDTH=1440, HEIGHT=3088, STRIP=(HEIGHT+4)/5;
    public final int rotation, left, top, right, bottom, displayWidth, displayHeight;
    public SafeArea(int rotation){
        this.rotation=rotation&3;
        displayWidth=(this.rotation%2==0)?WIDTH:HEIGHT;
        displayHeight=(this.rotation%2==0)?HEIGHT:WIDTH;
        left=this.rotation==1?STRIP:0;
        top=this.rotation==0?STRIP:0;
        right=displayWidth-(this.rotation==3?STRIP:0);
        bottom=displayHeight-(this.rotation==2?STRIP:0);
    }
    public int width(){return right-left;}
    public int height(){return bottom-top;}
    public boolean contains(float x,float y){return x>=left&&x<right&&y>=top&&y<bottom;}
    public int maskLeft(){return rotation==3?right:0;}
    public int maskTop(){return rotation==2?bottom:0;}
    public int maskWidth(){return rotation%2==0?WIDTH:STRIP;}
    public int maskHeight(){return rotation%2==0?STRIP:WIDTH;}
}
