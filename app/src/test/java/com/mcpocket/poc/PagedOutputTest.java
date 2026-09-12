package com.mcpocket.poc;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public final class PagedOutputTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void tinyChunksRoundTripChineseAndEmojiWithoutReplacement() throws Exception {
        File file=temp.newFile(); String original="測試🙂abcé尾"; Files.write(file.toPath(),original.getBytes(StandardCharsets.UTF_8));
        JSONObject args=new JSONObject().put("maxBytes",1); StringBuilder text=new StringBuilder();
        while(true) {
            JSONObject part=PagedOutput.file(file,args); text.append(part.getString("content"));
            if(!part.getBoolean("truncated")) break;
            args.put("offset",part.getLong("nextOffset")).put("version",part.getString("version"));
        }
        assertEquals(original,text.toString());
    }
    @Test public void changedFileAndInvalidCursorAreRejected() throws Exception {
        File file=temp.newFile(); Files.write(file.toPath(),"abc".getBytes(StandardCharsets.UTF_8));
        JSONObject first=PagedOutput.file(file,new JSONObject().put("maxBytes",1));
        JSONObject args=new JSONObject().put("offset",1).put("version",first.getString("version"));
        Files.write(file.toPath(),"xyz".getBytes(StandardCharsets.UTF_8));
        assertThrows(CommandRuntime.CommandInputException.class,()->PagedOutput.file(file,args));
        assertThrows(CommandRuntime.CommandInputException.class,()->PagedOutput.file(file,new JSONObject().put("offset",1)));
    }
    @Test public void processOffsetsAreRepeatableAndDoNotSplitEmoji() throws Exception {
        JSONObject snapshot=new JSONObject().put("stdout","a🙂b").put("stderr","err");
        JSONObject first=PagedOutput.process(new JSONObject(snapshot.toString()),new JSONObject().put("maxChars",2));
        assertEquals("a",first.getString("stdout"));
        JSONObject args=new JSONObject().put("stdoutOffset",first.getInt("stdoutNextOffset")).put("stderrOffset",first.getInt("stderrNextOffset")).put("maxChars",2);
        JSONObject second=PagedOutput.process(new JSONObject(snapshot.toString()),args);
        assertEquals("🙂",second.getString("stdout")); assertEquals("r",second.getString("stderr"));
        assertEquals(second.toString(),PagedOutput.process(new JSONObject(snapshot.toString()),args).toString());
    }
}
