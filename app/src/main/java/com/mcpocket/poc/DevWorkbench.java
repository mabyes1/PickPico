package com.mcpocket.poc;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Explicit development workspace and bounded job API. No change to legacy node.*. */
final class DevWorkbench {
    static JSONObject schema() throws JSONException {
        JSONObject p = new JSONObject().put("action", new JSONObject().put("type","string").put("enum",new JSONArray("[\"info\",\"project.create\",\"project.list\",\"files.list\",\"files.read\",\"files.write\",\"install\",\"run\",\"jobs.list\",\"job.status\",\"job.logs\",\"job.stop\",\"snapshot.create\",\"snapshot.list\",\"snapshot.diff\",\"snapshot.restore\",\"tunnel.open\"]")));
        for(String key : new String[]{"project","path","content","entry","jobId","snapshotId","serviceJobId","stream"})
            p.put(key,new JSONObject().put("type","string"));
        for(String key : new String[]{"offset","maxBytes","timeoutMs","port","ttlSeconds"}) p.put(key,new JSONObject().put("type","integer"));
        p.put("public",new JSONObject().put("type","boolean"));
        p.put("args",new JSONObject().put("type","array").put("maxItems",64).put("items",new JSONObject().put("type","string")));
        p.getJSONObject("project").put("description","Project name, required for project/files/install/run/snapshot/tunnel actions.");
        p.getJSONObject("jobId").put("description","Returned job UUID; required for job.status/logs/stop.");
        p.getJSONObject("path").put("description","Project-relative file path for files.read/write.");
        p.getJSONObject("entry").put("description","Project-relative JavaScript entry file for run.");
        p.getJSONObject("stream").put("enum",new JSONArray().put("stdout").put("stderr"));
        p.getJSONObject("maxBytes").put("description","Read page size 1..65536 bytes; up to three extra bytes preserve a UTF-8 character.");
        p.getJSONObject("snapshotId").put("description","Snapshot UUID for diff/restore; restore preserves the old tree in a backup and requires reinstall.");
        p.getJSONObject("serviceJobId").put("description","Active run job in this project; required with port and public=true for tunnel.open.");
        p.getJSONObject("ttlSeconds").put("description","Tunnel lifetime 30..3600 seconds; default 900.");
        p.getJSONObject("timeoutMs").put("description","Job deadline 1000..3600000 milliseconds; install defaults to 180000, run to 600000.");
        return new JSONObject().put("type","object").put("properties",p).put("required",new JSONArray().put("action")).put("additionalProperties",false);
    }
    private final Context context;
    private final File projects, jobs, history, bundle;
    private static final Class<?>[] SLOTS = {DevJobService.Slot0.class, DevJobService.Slot1.class, DevJobService.Slot2.class, DevJobService.Slot3.class};
    DevWorkbench(Context context) {
        this.context = context.getApplicationContext();
        projects = new File(context.getFilesDir(), "workspaces/dev-projects");
        jobs = new File(context.getFilesDir(), "dev-jobs");
        history = new File(context.getFilesDir(), "dev-history");
        bundle = new File(context.getFilesDir(), "dev-bundle-" + BuildConfig.VERSION_CODE);
    }
    static JSONObject read(File file) throws Exception { return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)); }
    static void write(File file, JSONObject object) throws Exception {
        file.getParentFile().mkdirs();
        File tmp = new File(file.getParentFile(), file.getName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp.toPath(), object.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    static File beneath(File root, String relative) throws Exception {
        File target = new File(root, relative).getCanonicalFile();
        if (!target.toPath().startsWith(root.getCanonicalFile().toPath()) || target.equals(root.getCanonicalFile()))
            throw new IllegalArgumentException("Path must remain inside project");
        return target;
    }
    private File project(String name, boolean create) throws Exception {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid project name");
        File root = beneath(projects, name);
        if (create) root.mkdirs();
        if (!root.isDirectory()) throw new IllegalArgumentException("Project not found");
        return root;
    }
    synchronized JSONObject call(JSONObject args) throws Exception {
        projects.mkdirs(); jobs.mkdirs(); history.mkdirs();
        String action = args.getString("action");
        if (action.equals("info")) return new JSONObject().put("projectsRoot", "dev-projects")
                .put("maxConcurrentJobs", 4).put("runtime", "nodejs-mobile 18.20.4")
                .put("packagePolicy", "npm registry dependencies; no lifecycle scripts or native builds")
                .put("runPolicy", "JavaScript entry + argv; not a shell/npm-script emulator")
                .put("versioning", "source snapshots, diff, non-destructive restore; Git not bundled")
                .put("tunnelProvider", "https://localtunnel.me")
                .put("actions", new JSONArray("[\"project.create\",\"project.list\",\"files.list\",\"files.read\",\"files.write\",\"install\",\"run\",\"jobs.list\",\"job.status\",\"job.logs\",\"job.stop\",\"snapshot.create\",\"snapshot.list\",\"snapshot.diff\",\"snapshot.restore\",\"tunnel.open\"]"));
        if (action.equals("project.list")) return new JSONObject().put("projects", new JSONArray(projects.list()));
        if (action.equals("jobs.list")) {
            JSONArray all = new JSONArray(); File[] dirs = jobs.listFiles();
            if (dirs != null) for (File dir : dirs) if (new File(dir,"status.json").isFile()) all.put(status(dir.getName()));
            return new JSONObject().put("jobs", all);
        }
        if (action.startsWith("job.")) {
            String id = args.getString("jobId"); JSONObject state = status(id); File dir = job(id);
            if (action.equals("job.status")) return state;
            if (action.equals("job.stop")) {
                if (state.optBoolean("running")) {
                    Files.write(new File(dir,"cancel").toPath(),new byte[]{1});
                    int pid = livePid(state.getInt("slot"));
                    state.put("status","cancelled").put("running",false).put("completedAt",System.currentTimeMillis());
                    write(new File(dir,"status.json"), state);
                    if (pid > 0 && pid == state.optInt("pid", -1)) Os.kill(pid, OsConstants.SIGKILL);
                }
                return status(id);
            }
            if (action.equals("job.logs")) {
                String stream = args.optString("stream","stdout");
                if (!stream.equals("stdout") && !stream.equals("stderr")) throw new IllegalArgumentException("Invalid stream");
                return readSlice(new File(dir, stream + ".log"), args.optLong("offset",0), args.optInt("maxBytes",16384)).put("jobId",id).put("stream",stream);
            }
            throw new IllegalArgumentException("Unknown job action");
        }
        String name = args.getString("project"); File root = project(name, action.equals("project.create"));
        if (action.equals("project.create")) {
            File pkg = new File(root,"package.json");
            if (!pkg.exists()) write(pkg, new JSONObject().put("name",name.toLowerCase(Locale.ROOT)).put("version","1.0.0").put("private",true));
            return new JSONObject().put("project",name).put("path","dev-projects/"+name);
        }
        if (action.equals("files.list")) {
            JSONArray entries = new JSONArray(); scan(root,root,entries,0); return new JSONObject().put("entries",entries);
        }
        if (action.equals("files.read")) return readSlice(beneath(root,args.getString("path")),args.optLong("offset",0),args.optInt("maxBytes",16384));
        if (action.equals("files.write")) {
            requireIdle(name); File file = beneath(root,args.getString("path")); String content=args.getString("content");
            if(content.length()>1048576) throw new IllegalArgumentException("File write limited to 1 MiB characters");
            file.getParentFile().mkdirs(); File tmp = new File(file.getParentFile(),".pico-write-"+UUID.randomUUID());
            Files.write(tmp.toPath(),content.getBytes(StandardCharsets.UTF_8)); Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
            return new JSONObject().put("written",true).put("bytes",file.length());
        }
        if(action.startsWith("snapshot.")) return snapshot(action,name,root,args);
        if(action.equals("install") || action.equals("run") || action.equals("tunnel.open")) return start(action,name,root,args);
        throw new IllegalArgumentException("Unknown development action: "+action);
    }
    private File job(String id) throws Exception {
        if(!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid jobId");
        File dir=beneath(jobs,id); if(!dir.isDirectory())throw new IllegalArgumentException("Job not found");return dir;
    }
    private int livePid(int slot) {
        List<ActivityManager.RunningAppProcessInfo> processes=context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if(processes!=null)for(ActivityManager.RunningAppProcessInfo p:processes)
            if(p.processName.equals(context.getPackageName()+":dev"+slot))return p.pid;
        return -1;
    }
    private JSONObject status(String id) throws Exception {
        File dir=job(id); JSONObject s=read(new File(dir,"status.json"));
        String phase=s.optString("status");
        boolean active=phase.equals("starting")||phase.equals("running");
        int pid = livePid(s.getInt("slot"));
        boolean running=pid>0 && pid==s.optInt("pid",-1);
        boolean starting=phase.equals("starting") && System.currentTimeMillis()-s.getLong("createdAt")<15000;
        if(active&&!running&&!starting){
            File exit=new File(dir,"exit.json");
            if(exit.isFile()){
                JSONObject outcome=read(exit);int code=outcome.getInt("exitCode");
                s.put("status",code==0?"completed":"failed").put("exitCode",code).put("completedAt",outcome.getLong("completedAt"));
            }else{s.put("status","interrupted").put("exitCodeKnown",false);}
            write(new File(dir,"status.json"),s);
        }
        s.put("running",running||starting);
        if(new File(dir,"result.json").isFile()){
            JSONObject result=read(new File(dir,"result.json"));
            if("tunnel".equals(s.optString("operation"))&&!s.optBoolean("running"))result.put("status","closed");
            s.put("result",result);
        }
        return s;
    }
    private void requireIdle(String project) throws Exception {
        File[] dirs=jobs.listFiles();if(dirs==null)return;
        for(File dir:dirs)if(new File(dir,"status.json").isFile()){
            JSONObject s=status(dir.getName());if(project.equals(s.optString("project"))&&s.optBoolean("running"))throw new IllegalArgumentException("Project has active jobs; stop them before changing files or dependencies");
        }
    }
    private JSONObject start(String action,String name,File root,JSONObject args)throws Exception{
        if(action.equals("install"))requireIdle(name);
        // Reads/runs may coexist; installation must never race a run.
        for(File d:Objects.requireNonNull(jobs.listFiles()))if(new File(d,"status.json").isFile()){
            JSONObject s=status(d.getName());if(name.equals(s.optString("project"))&&s.optBoolean("running")&&s.optString("operation").equals("install"))throw new IllegalArgumentException("Dependency installation is still running");
        }
        boolean[] occupied=new boolean[4];
        for(int i=0;i<4;i++)occupied[i]=livePid(i)>0;
        for(File d:Objects.requireNonNull(jobs.listFiles()))if(new File(d,"status.json").isFile()){
            JSONObject s=status(d.getName());if(s.optBoolean("running"))occupied[s.getInt("slot")]=true;
        }
        int slot=0;while(slot<4&&occupied[slot])slot++;if(slot==4)throw new IllegalArgumentException("All four development slots are busy");
        prepareBundle();
        String id=UUID.randomUUID().toString();File dir=new File(jobs,id);dir.mkdirs();
        String operation=action.equals("tunnel.open")?"tunnel":action;
        long timeout=args.optLong("timeoutMs",action.equals("install")?180000:600000);
        if(timeout<1000||timeout>3600000)throw new IllegalArgumentException("timeoutMs must be 1000..3600000");
        JSONObject cfg=new JSONObject().put("operation",operation).put("resultFile",new File(dir,"result.json").getAbsolutePath()).put("exitFile",new File(dir,"exit.json").getAbsolutePath()).put("cache",new File(context.getCacheDir(),"dev-npm").getAbsolutePath());
        if(action.equals("run")){
            File entry=beneath(root,args.getString("entry"));if(!entry.isFile())throw new IllegalArgumentException("Entry not found");
            cfg.put("entry",entry.getAbsolutePath()).put("args",args.optJSONArray("args")==null?new JSONArray():args.getJSONArray("args"));
        }
        if(action.equals("tunnel.open")){
            int port=args.getInt("port"),ttl=args.optInt("ttlSeconds",900);
            if(port<1024||port>65535||port==8765||ttl<30||ttl>3600)throw new IllegalArgumentException("Invalid tunnel port or TTL (30..3600 seconds)");
            if(!args.optBoolean("public",false))throw new IllegalArgumentException("public=true is required to expose a local development service");
            String serviceId=args.getString("serviceJobId");JSONObject service=status(serviceId);
            if(!service.optBoolean("running")||!name.equals(service.optString("project"))||!"run".equals(service.optString("operation")))throw new IllegalArgumentException("Tunnel requires an active run job in the same project");
            try(java.net.Socket socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),1500);}
            cfg.put("port",port).put("ttlSeconds",ttl)
                    .put("serviceStatusFile",new File(job(serviceId),"status.json").getAbsolutePath())
                    .put("serviceCancelFile",new File(job(serviceId),"cancel").getAbsolutePath());timeout=(ttl+30L)*1000;
        }
        JSONObject state=new JSONObject().put("jobId",id).put("project",name).put("operation",operation).put("slot",slot).put("status","starting").put("createdAt",System.currentTimeMillis()).put("cwd",root.getAbsolutePath()).put("runner",new File(bundle,"runner.cjs").getAbsolutePath()).put("timeoutMs",timeout);
        write(new File(dir,"config.json"),cfg);write(new File(dir,"status.json"),state);
        try{context.startForegroundService(new Intent(context,SLOTS[slot]).putExtra("jobId",id));}
        catch(RuntimeException e){state.put("status","failed").put("error",e.toString());write(new File(dir,"status.json"),state);throw e;}
        return state.put("running",true).put("next","dev action=job.status/job.logs with jobId; job.stop to stop");
    }
    private void prepareBundle()throws Exception{
        if(new File(bundle,"ready").isFile())return;
        bundle.mkdirs();
        try(ZipInputStream zip=new ZipInputStream(context.getAssets().open("devtools.zip"))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){File out=beneath(bundle,entry.getName());if(entry.isDirectory())out.mkdirs();else{out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){byte[] buffer = new byte[8192]; int n; while ((n = zip.read(buffer)) != -1) stream.write(buffer, 0, n);}}}}
        Files.write(new File(bundle,"ready").toPath(),new byte[]{1});
    }
    private static JSONObject readSlice(File file,long offset,int limit)throws Exception{
        if(offset<0||limit<1||limit>65536)throw new IllegalArgumentException("Invalid offset or maxBytes");
        if(!file.exists())return new JSONObject().put("text","").put("nextOffset",offset).put("eof",true);
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            long start=Math.min(offset,input.length());input.seek(start);
            byte[] b=new byte[(int)Math.min(limit+3L,input.length()-start)];input.readFully(b);
            int end=Math.min(limit,b.length);
            while(end<b.length && (b[end]&0xc0)==0x80)end++;
            long next=start+end;
            return new JSONObject().put("text",new String(b,0,end,StandardCharsets.UTF_8)).put("nextOffset",next).put("eof",next>=input.length());
        }
    }
    private static void scan(File root,File dir,JSONArray result,int depth)throws Exception{
        if(depth>12||result.length()>2000)throw new IllegalArgumentException("Project listing limit exceeded");
        File[] files=dir.listFiles();if(files==null)return;for(File f:files){if(f.getName().equals("node_modules")||f.getName().equals(".git"))continue;
            if(Files.isSymbolicLink(f.toPath()))throw new IllegalArgumentException("Symlinks are not supported in source snapshots");
            if(f.isDirectory())scan(root,f,result,depth+1);else result.put(root.toPath().relativize(f.toPath()).toString().replace('\\','/'));}
    }
    private JSONObject snapshot(String action,String name,File root,JSONObject args)throws Exception{
        requireIdle(name);File store=new File(history,name);store.mkdirs();
        if(action.equals("snapshot.list"))return new JSONObject().put("snapshots",new JSONArray(store.list((dir, child) -> child.matches("[a-f0-9-]{36}"))));
        if(action.equals("snapshot.create")){
            String id=UUID.randomUUID().toString();File dest=new File(store,"pending-"+id);dest.mkdirs();JSONArray files=new JSONArray();scan(root,root,files,0);long bytes=0;
            for(int i=0;i<files.length();i++){String rel=files.getString(i);File src=beneath(root,rel);bytes+=src.length();if(bytes>50*1024*1024)throw new IllegalArgumentException("Snapshot source exceeds 50 MiB");File out=beneath(dest,rel);out.getParentFile().mkdirs();Files.copy(src.toPath(),out.toPath());}
            Files.move(dest.toPath(),new File(store,id).toPath());
            return new JSONObject().put("snapshotId",id).put("files",files.length()).put("excludes","node_modules and .git");
        }
        String id=args.getString("snapshotId");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid snapshotId");File saved=beneath(store,id);if(!saved.isDirectory())throw new IllegalArgumentException("Snapshot not found");
        JSONArray before=new JSONArray(),after=new JSONArray();scan(saved,saved,before,0);scan(root,root,after,0);Set<String> names=new TreeSet<>();for(int i=0;i<before.length();i++)names.add(before.getString(i));for(int i=0;i<after.length();i++)names.add(after.getString(i));JSONArray changes=new JSONArray();
        for(String rel:names){File a=beneath(saved,rel),b=beneath(root,rel);String change=!a.exists()?"added":!b.exists()?"deleted":sameContents(a,b)?"":"modified";if(!change.isEmpty())changes.put(new JSONObject().put("path",rel).put("change",change));}
        if(action.equals("snapshot.diff"))return new JSONObject().put("changes",changes);
        if(!action.equals("snapshot.restore"))throw new IllegalArgumentException("Unknown snapshot action");
        File staging=new File(history,"restore-"+UUID.randomUUID());staging.mkdirs();
        for(int i=0;i<before.length();i++){String rel=before.getString(i);File out=beneath(staging,rel);out.getParentFile().mkdirs();Files.copy(beneath(saved,rel).toPath(),out.toPath());}
        File backup=new File(history,"backup-"+name+"-"+UUID.randomUUID());
        Files.move(root.toPath(),backup.toPath());
        try{Files.move(staging.toPath(),root.toPath());}catch(Exception e){Files.move(backup.toPath(),root.toPath());throw e;}
        return new JSONObject().put("restored",true).put("backup",backup.getName()).put("reinstallDependencies",true).put("changes",changes);
    }
    private static boolean sameContents(File a, File b) throws IOException {
        if(a.length()!=b.length())return false;
        try(InputStream left=new BufferedInputStream(new FileInputStream(a));InputStream right=new BufferedInputStream(new FileInputStream(b))){
            int value;while((value=left.read())!=-1)if(value!=right.read())return false;return right.read()==-1;
        }
    }
}
