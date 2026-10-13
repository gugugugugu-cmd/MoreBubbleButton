"""Local: source invariants only. --java: CI-only behavioral tests of actual helper with Android stubs."""
import pathlib, sys, tempfile, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/floatwindow/morebubblebutton'
s = (SRC / 'BubbleIconDoubleTap.java').read_text()
main = (SRC / 'MoreBubbleHookModule.java').read_text()
assert 'dispatchTouchEvent' not in main and 'GestureDetector' not in main
assert 'new BubbleIconDoubleTap(this).install(cl)' in main
for required in ['read(stack, field) == listener', '"$r8$classId"', 'read(bubble, "mIconView") == icon',
                 'ViewConfiguration.getDoubleTapTimeout()', 'Invoker.Type.ORIGIN',
                 'new Handler(view.getHandler().getLooper())', 'matched != bubble',
                 'onViewDetachedFromWindow', 'conversion.remove()', 'throw new NoSuchFieldException']:
    assert required in s, required
assert 'setOnTouchListener' not in s and 'dispatchTouchEvent' not in s
assert 'Chain ' not in s and 'chain' not in s[s.index('private final class PendingClick'):s.index('private boolean convert')]
assert 'KEY_BUBBLE_DOUBLE_TAP_FULLSCREEN, true' in (SRC / 'ModuleSettings.java').read_text()
print('PASS: source invariants (not device/runtime testing)')
if '--java' not in sys.argv:
    sys.exit(0)
assert __import__('os').environ.get('GITHUB_ACTIONS') == 'true', 'Java compilation permitted only on GitHub Actions'
files = {
'android/os/Looper.java': '''package android.os; public class Looper { static final Looper L=new Looper(); public static Looper myLooper(){return L;} }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long now; public static long uptimeMillis(){return now;} }''',
'android/os/Handler.java': '''package android.os; import java.util.*; public class Handler {
 public static class Job {Handler h; Runnable r; long t; Job(Handler h,Runnable r,long t){this.h=h;this.r=r;this.t=t;}}
 public static List<Job> jobs=new ArrayList<>(); Looper l; public Handler(Looper l){this.l=l;}
 public Looper getLooper(){return l;} public boolean postDelayed(Runnable r,long delay){jobs.add(new Job(this,r,SystemClock.now+delay));return true;}
 public void removeCallbacks(Runnable r){jobs.removeIf(j->j.h==this&&j.r==r);}
 public void removeCallbacksAndMessages(Object token){jobs.removeIf(j->j.h==this);}
 public static void advance(long n){SystemClock.now=n; boolean again; do {again=false;for(Job j:new ArrayList<>(jobs))if(j.t<=n&&jobs.remove(j)){j.r.run();again=true;}}while(again);}
}''',
'android/content/Context.java': 'package android.content; public class Context {}',
'android/view/View.java': '''package android.view; import android.os.*; import android.content.*; import java.util.*; public class View {
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public Handler h=new Handler(Looper.myLooper()); public boolean attached=true; List<OnAttachStateChangeListener> ls=new ArrayList<>();
 public Context getContext(){return new Context();} public Handler getHandler(){return h;} public boolean isAttachedToWindow(){return attached;}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){ls.add(l);} public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){ls.remove(l);}
 public void detach(){attached=false;for(var l:new ArrayList<>(ls))l.onViewDetachedFromWindow(this);}
}''',
'android/view/ViewConfiguration.java': '''package android.view; import android.content.*; public class ViewConfiguration {
 public static int getDoubleTapTimeout(){return 300;} public static int getLongPressTimeout(){return 500;}
 public static ViewConfiguration get(Context c){return new ViewConfiguration();} public int getScaledTouchSlop(){return 8;}
}''',
'android/view/MotionEvent.java': '''package android.view; public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 public int a;public long t; public float x,y; public int pointers=1;
 public MotionEvent(int a,long t,float x,float y){this.a=a;this.t=t;this.x=x;this.y=y;}
 public int getActionMasked(){return a;} public long getEventTime(){return t;} public float getRawX(){return x;} public float getRawY(){return y;}public int getPointerCount(){return pointers;}
}''',
'android/app/TaskInfo.java': 'package android.app; public class TaskInfo {public int taskId=42;public Object token=new Object();}',
'android/util/Log.java': '''package android.util; public class Log {
 public static int i(String t,String s){return 0;}public static int d(String t,String s){return 0;}
 public static int w(String t,String s,Throwable x){return 0;}public static int e(String t,String s,Throwable x){return 0;}
}''',
'io/github/libxposed/api/XposedInterface.java': '''package io.github.libxposed.api; public interface XposedInterface {
 interface Invoker { enum Type {ORIGIN} Invoker setType(Type t);Object invoke(Object receiver,Object... args) throws Exception; }
 interface Chain {Object getThisObject();Object getArg(int i);Object proceed() throws Throwable;}
 interface Interceptor {Object intercept(Chain c) throws Throwable;} interface HookBuilder {void intercept(Interceptor i);}
}''',
'com/floatwindow/morebubblebutton/MoreBubbleHookModule.java': '''package com.floatwindow.morebubblebutton;
import java.lang.reflect.*;import io.github.libxposed.api.*;
public class MoreBubbleHookModule { public XposedInterface.HookBuilder hook(Method m){return i->{};}
 public XposedInterface.Invoker getInvoker(Method m){return new XposedInterface.Invoker(){
 public XposedInterface.Invoker setType(Type t){if(t!=Type.ORIGIN)throw new AssertionError();return this;}
 public Object invoke(Object r,Object... args)throws Exception{return m.invoke(r,args);}};}
}''',
'com/floatwindow/morebubblebutton/ModuleSettings.java': '''package com.floatwindow.morebubblebutton; import android.content.*;
public class ModuleSettings {static boolean enabled=true;public static boolean isDoubleTapFullscreenEnabled(Context c){return enabled;}}''',
}
files['com/floatwindow/morebubblebutton/IconTest.java'] = r'''package com.floatwindow.morebubblebutton;
import android.os.*;import android.view.*;import android.app.*;import java.util.*;import java.lang.reflect.*;
public class IconTest {
 public static class Listener {public int $r8$classId;public Stack this$0;public int singles;public void onClick(View v){singles++;}}
 public static class TouchListener {public int $r8$classId;public Stack this$0;public boolean performedLongClick,movedEnough;}
 public static class Bubble {public View mIconView=new View();public Wrapper mBubbleTaskView=new Wrapper();public int mTaskId=42;public boolean isApp(){return true;}}
 public static class Wrapper {public TaskView taskView=new TaskView();}
 public static class TaskView {public Controller mTaskViewController=new Controller();public TaskController mTaskViewTaskController=new TaskController();}
 public static class TaskController {public TaskInfo mTaskInfo=new TaskInfo();}
 public static class Owner {public Data mBubbleData;}
 public static class Controller {public Owner this$0=new Owner();public int moves;public void moveTaskViewToFullscreen(TaskController t){moves++;}}
 public static class Data {public List<Bubble> mBubbles=new ArrayList<>();public Bubble getBubbleInStackWithTaskId(int id){for(Bubble b:mBubbles)if(b.mTaskId==id)return b;return null;}}
 public static class Stack {public Data mBubbleData=new Data();public Listener mBubbleClickListener=new Listener();public TouchListener mBubbleTouchListener=new TouchListener();public boolean mIsExpanded=true,mIsExpansionAnimating,mIsBubbleSwitchAnimating,mShowedUserEducationInTouchListenerActive;}
 static class Fixture {
  BubbleIconDoubleTap h=new BubbleIconDoubleTap(new MoreBubbleHookModule());Stack stack=new Stack();Bubble a=new Bubble(),b=new Bubble();
  Fixture()throws Exception {
   Handler.jobs.clear();SystemClock.now=1000;ModuleSettings.enabled=true;
   stack.mBubbleClickListener.this$0=stack;stack.mBubbleTouchListener.this$0=stack;stack.mBubbleData.mBubbles.add(a);stack.mBubbleData.mBubbles.add(b);b.mTaskId=43;b.mBubbleTaskView.taskView.mTaskViewTaskController.mTaskInfo.taskId=43;
   for(Bubble x:stack.mBubbleData.mBubbles)x.mBubbleTaskView.taskView.mTaskViewController.this$0.mBubbleData=stack.mBubbleData;
   set(h,"bubbleClass",Bubble.class);set(h,"clickMethod",Listener.class.getMethod("onClick",View.class));
  }
  boolean click(Bubble x)throws Exception{return (boolean)call(h,"handleClick",new Class[]{Object.class,Object.class,View.class},stack,stack.mBubbleClickListener,x.mIconView);}
  void touch(Bubble x,int action,long t,float dx)throws Exception{SystemClock.now=t;call(h,"observeTouch",new Class[]{Object.class,Object.class,View.class,MotionEvent.class},stack,stack.mBubbleTouchListener,x.mIconView,new MotionEvent(action,t,dx,0));}
  int moves(){return a.mBubbleTaskView.taskView.mTaskViewController.moves+b.mBubbleTaskView.taskView.mTaskViewController.moves;}
  int singles(){return stack.mBubbleClickListener.singles;}
 }
 static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
 static Object call(Object o,String n,Class[] types,Object... args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  Fixture f=new Fixture();check(f.click(f.a),"defer first");check(f.singles()==0,"no immediate collapse");Handler.advance(1300);check(f.singles()==1&&f.moves()==0,"one original single");Handler.advance(2000);check(f.singles()==1,"not repeated");
  f=new Fixture();f.click(f.a);SystemClock.now=1100;f.click(f.a);Handler.advance(2000);check(f.moves()==1&&f.singles()==0,"same bubble double");
  f=new Fixture();f.click(f.a);SystemClock.now=1100;f.click(f.b);Handler.advance(1500);check(f.moves()==0&&f.singles()==2,"different icons single only");
  f=new Fixture();f.click(f.a);f.a.mIconView.detach();Handler.advance(2000);check(f.singles()==0&&f.moves()==0,"detach cleanup");
  f=new Fixture();f.click(f.a);ModuleSettings.enabled=false;SystemClock.now=1100;check(!f.click(f.a),"disabled current proceeds");check(f.singles()==1&&f.moves()==0,"disabled drains first");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);f.touch(f.a,2,1150,30);Handler.advance(2000);check(f.moves()==0&&f.singles()==0,"drag clears pending");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);f.touch(f.a,3,1150,0);Handler.advance(2000);check(f.moves()==0&&f.singles()==0,"cancel clears pending");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1100,0);Handler.advance(1600);check(!f.click(f.a)&&f.moves()==0,"long press never converts");
  f=new Fixture();f.click(f.a);f.touch(f.a,0,1290,0);Handler.advance(1310);check(f.singles()==0,"second down pauses first");f.touch(f.a,1,1340,0);f.click(f.a);check(f.moves()==1&&f.singles()==0,"second up converts after pause");
  f=new Fixture();f.click(f.a);f.a.mBubbleTaskView.taskView.mTaskViewTaskController.mTaskInfo.taskId=99;SystemClock.now=1100;f.click(f.a);check(f.moves()==0&&f.singles()==1,"task mismatch safe single fallback");
  f=new Fixture();f.click(f.a);f.a.mBubbleTaskView=null;SystemClock.now=1100;f.click(f.a);check(f.moves()==0&&f.singles()==1,"missing task safe fallback");
  f=new Fixture();check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},f.stack.mBubbleClickListener,Listener.class,"mBubbleClickListener")==f.stack,"correct scope");
  Listener other=new Listener();other.this$0=f.stack;check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},other,Listener.class,"mBubbleClickListener")==null,"identity excludes merged role");
  f.stack.mBubbleClickListener.$r8$classId=1;check(call(f.h,"scopedStack",new Class[]{Object.class,Class.class,String.class},f.stack.mBubbleClickListener,Listener.class,"mBubbleClickListener")==null,"classId excludes flyout");
  try{call(f.h,"read",new Class[]{Object.class,String.class},f.stack,"notPresent");throw new AssertionError("missing field hidden");}catch(InvocationTargetException expected){check(expected.getCause() instanceof NoSuchFieldException,"missing distinct from null");}
  System.out.println("PASS: 15 helper behavioral cases with stubs; NOT on-device testing");
 }
}'''
with tempfile.TemporaryDirectory() as directory:
    root = pathlib.Path(directory)
    for name, text in files.items():
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    helper = root / 'com/floatwindow/morebubblebutton/BubbleIconDoubleTap.java'
    helper.write_text(s)
    subprocess.run(['javac', '-d', str(root / 'classes')] + [str(p) for p in root.rglob('*.java')], check=True)
    subprocess.run(['java', '-cp', str(root / 'classes'), 'com.floatwindow.morebubblebutton.IconTest'], check=True)
