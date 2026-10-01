package ca.screensafe.app;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Color;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    Handler handler=new Handler(); TextView status; Button enable,restore,end;
    final Runnable refresh=new Runnable(){public void run(){
        SessionRunner runner=SessionRunner.current;
        String state=runner==null?"Activation needed\nConnect to your computer and open Start Screen Safe.":runner.status;
        TouchFilterService filter=TouchFilterService.current;
        if(filter!=null&&filter.filtering)state+="\nTouch filter active";
        status.setText(state);
        enable.setEnabled(runner!=null && !runner.busy);
        restore.setEnabled(runner!=null && runner.busy);
        end.setEnabled(runner!=null);
        handler.postDelayed(this,400);
    }};
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.WHITE);v.setTextSize(size);return v;}
    public void onCreate(Bundle saved){super.onCreate(saved);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(16,30,40));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(26),dp(66),dp(26),dp(55));
        scroll.addView(content);
        // Edge-to-edge windows must keep interactive content outside the reported system bars.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener(){
            public android.view.WindowInsets onApplyWindowInsets(View view,android.view.WindowInsets insets){
                android.graphics.Insets nav=insets.getInsets(android.view.WindowInsets.Type.navigationBars());
                view.setPadding(nav.left,0,nav.right,nav.bottom);
                return insets;
            }
        });
        content.addView(label("Screen Safe",32));
        TextView sub=label("Keep your screen below the damaged strip.",17);sub.setPadding(0,dp(12),0,dp(30));content.addView(sub);
        status=label("Connecting…",21);status.setMinHeight(dp(120));content.addView(status);
        enable=new Button(this);enable.setText("Protect top 20%");content.addView(enable);
        restore=new Button(this);restore.setText("Restore full screen");content.addView(restore);
        end=new Button(this);end.setText("End activated session");content.addView(end);
        TextView note=label("S23 Ultra · Adaptive layout preview\n\nThe lock-screen photo now fills the usable area. Rotation can still blink; that fix is in progress.\n\nIf touch forwarding or restoration stops working, reconnect USB and run Restore Screen on your computer.",14);
        note.setPadding(0,dp(25),0,0);content.addView(note);setContentView(scroll);
        enable.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(SessionRunner.current!=null)SessionRunner.current.request("START");}});
        restore.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(SessionRunner.current!=null)SessionRunner.current.request("STOP");}});
        end.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(SessionRunner.current!=null)SessionRunner.current.request("EXIT");}});
    }
    protected void onResume(){super.onResume();handler.post(refresh);}
    protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
