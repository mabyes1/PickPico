#include <jni.h>
#include <node.h>
#include <unistd.h>
#include <fcntl.h>

#include <string>
#include <vector>

extern "C"
JNIEXPORT jint JNICALL
Java_com_mcpocket_poc_NodeRuntimeBridge_startNode(
        JNIEnv* env,
        jclass,
        jstring cwd,
        jobjectArray arguments) {
    const char* cwd_chars = env->GetStringUTFChars(cwd, nullptr);
    if (cwd_chars == nullptr) {
        return 126;
    }

    int chdir_result = chdir(cwd_chars);
    env->ReleaseStringUTFChars(cwd, cwd_chars);
    if (chdir_result != 0) {
        return 126;
    }

    const jsize argument_count = env->GetArrayLength(arguments);
    std::vector<std::string> storage;
    storage.reserve(argument_count);

    for (jsize index = 0; index < argument_count; index++) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, index));
        const char* chars = env->GetStringUTFChars(value, nullptr);
        if (chars == nullptr) {
            env->DeleteLocalRef(value);
            return 126;
        }
        storage.emplace_back(chars);
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }

    std::vector<char*> argv;
    argv.reserve(storage.size());
    for (std::string& value : storage) {
        argv.push_back(value.data());
    }

    return node::Start(static_cast<int>(argv.size()), argv.data());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_mcpocket_poc_NodeRuntimeBridge_startNodeCaptured(
        JNIEnv* env, jclass cls, jstring cwd, jobjectArray arguments,
        jstring stdoutPath, jstring stderrPath) {
    const char* out = env->GetStringUTFChars(stdoutPath, nullptr);
    const char* err = env->GetStringUTFChars(stderrPath, nullptr);
    if (!out || !err) {
        if (out) env->ReleaseStringUTFChars(stdoutPath, out);
        if (err) env->ReleaseStringUTFChars(stderrPath, err);
        return 126;
    }
    int outfd = open(out, O_WRONLY | O_CREAT | O_APPEND, 0600);
    int errfd = open(err, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(stdoutPath, out);
    env->ReleaseStringUTFChars(stderrPath, err);
    if (outfd < 0 || errfd < 0) {
        if (outfd >= 0) close(outfd);
        if (errfd >= 0) close(errfd);
        return 126;
    }
    int outResult = dup2(outfd, STDOUT_FILENO);
    int errResult = dup2(errfd, STDERR_FILENO);
    close(outfd); close(errfd);
    if (outResult < 0 || errResult < 0) return 126;
    return Java_com_mcpocket_poc_NodeRuntimeBridge_startNode(env, cls, cwd, arguments);
}
