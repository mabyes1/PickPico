package com.mcpocket.poc;
import android.content.Context;
import android.app.ActivityManager;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class DevWorkbenchTest {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 private DevWorkbench dev;
 private File files;
 private ActivityManager manager;
 @Before public void setup() throws Exception {
  Context c=mock(Context.class);files=temp.newFolder("files");when(c.getApplicationContext()).thenReturn(c);when(c.getFilesDir()).thenReturn(files);
  manager=mock(ActivityManager.class);when(c.getSystemService(ActivityManager.class)).thenReturn(manager);when(manager.getRunningAppProcesses()).thenReturn(Collections.emptyList());when(c.getPackageName()).thenReturn("com.mcpocket.poc");
  dev=new DevWorkbench(c);call("project.create");
 }
 private JSONObject call(String action)throws Exception{return dev.call(new JSONObject().put("action",action).put("project","example"));}
 private void write(String path,String text)throws Exception{dev.call(new JSONObject().put("action","files.write").put("project","example").put("path",path).put("content",text));}
 @Test public void rejectsTraversalWithoutTouchingOutside()throws Exception{
  try{write("../../outside","oops");fail();}catch(IllegalArgumentException expected){}
  assertFalse(new File(files,"outside").exists());
 }
 @Test public void snapshotDiffAndRestorePreservePreviousTree()throws Exception{
  write("src/main.js","old");String snapshot=call("snapshot.create").getString("snapshotId");write("src/main.js","new");write("later.txt","keep in backup");
  JSONObject args=new JSONObject().put("project","example").put("snapshotId",snapshot);
  assertEquals(2,dev.call(args.put("action","snapshot.diff")).getJSONArray("changes").length());
  JSONObject restored=dev.call(args.put("action","snapshot.restore"));assertTrue(restored.getBoolean("restored"));
  assertEquals("old",new String(Files.readAllBytes(new File(files,"workspaces/dev-projects/example/src/main.js").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  assertTrue(new File(files,"dev-history/"+restored.getString("backup")+"/later.txt").exists());
 }
 @Test public void logPagingReturnsByteOffsetsAndEof()throws Exception{
  write("read.txt","abcdef");JSONObject q=new JSONObject().put("action","files.read").put("project","example").put("path","read.txt").put("maxBytes",3);
  JSONObject first=dev.call(q);assertEquals("abc",first.getString("text"));assertFalse(first.getBoolean("eof"));
  JSONObject last=dev.call(q.put("offset",first.getLong("nextOffset")));assertEquals("def",last.getString("text"));assertTrue(last.getBoolean("eof"));
 }
 @Test public void reusedSlotDoesNotMakeOldJobAlive()throws Exception{
  String id=java.util.UUID.randomUUID().toString();File dir=new File(files,"dev-jobs/"+id);
  DevWorkbench.write(new File(dir,"status.json"),new JSONObject().put("status","running").put("slot",0).put("pid",100).put("createdAt",0));
  ActivityManager.RunningAppProcessInfo process=new ActivityManager.RunningAppProcessInfo();process.processName="com.mcpocket.poc:dev0";process.pid=101;
  when(manager.getRunningAppProcesses()).thenReturn(Collections.singletonList(process));
  JSONObject state=dev.call(new JSONObject().put("action","job.status").put("jobId",id));
  assertFalse(state.getBoolean("running"));assertEquals("interrupted",state.getString("status"));
 }
 @Test public void utf8PageDoesNotSplitChineseOrEmoji()throws Exception{
  write("unicode.txt","中🙂文");JSONObject q=new JSONObject().put("action","files.read").put("project","example").put("path","unicode.txt").put("maxBytes",1);
  JSONObject first=dev.call(q);assertEquals("中",first.getString("text"));assertEquals(3,first.getInt("nextOffset"));
  JSONObject second=dev.call(q.put("offset",3));assertEquals("🙂",second.getString("text"));assertEquals(7,second.getInt("nextOffset"));
 }
 @Test public void processExitReceiptRetainsExitCode()throws Exception{
  String id=java.util.UUID.randomUUID().toString();File dir=new File(files,"dev-jobs/"+id);
  DevWorkbench.write(new File(dir,"status.json"),new JSONObject().put("status","running").put("slot",0).put("pid",100).put("createdAt",0));
  DevWorkbench.write(new File(dir,"exit.json"),new JSONObject().put("exitCode",7).put("completedAt",123));
  JSONObject state=dev.call(new JSONObject().put("action","job.status").put("jobId",id));
  assertEquals(7,state.getInt("exitCode"));assertEquals("failed",state.getString("status"));assertFalse(state.getBoolean("running"));
 }
}
