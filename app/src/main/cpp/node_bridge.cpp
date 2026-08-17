#include <jni.h>
#include <node.h>
#include <unistd.h>

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
