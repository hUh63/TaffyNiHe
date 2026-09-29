#include <stdio.h>
#include <inttypes.h>

#include "disassembler.h"
#include "capstone_jni_FastDisassembler.h"

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    nativeInitialize
 * Signature: (ZI)J
 */
JNIEXPORT jlong JNICALL Java_capstone_jni_FastDisassembler_nativeInitialize
  (JNIEnv *env, jclass cls, jboolean is64Bit, jint mode) {
  t_capstone capstone = malloc(sizeof(struct capstone));
  capstone->is64Bit = is64Bit;
  if(is64Bit) {
    capstone->map2U = (map_reg) &map_arm64_reg_2U;
    capstone->map2C = (map_reg) &map_arm64_reg_2C;
  } else {
    capstone->map2U = (map_reg) &map_arm_reg_2U;
    capstone->map2C = (map_reg) &map_arm_reg_2C;
  }
  cs_err err = cs_open(is64Bit ? CS_ARCH_ARM64 : CS_ARCH_ARM, mode, &capstone->handle);
  if (err != CS_ERR_OK) {
    printf("nativeInitialize err=%d\n", err);
    free(capstone);
    return 0;
  } else {
    return (jlong) capstone;
  }
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    nativeDestroy
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL Java_capstone_jni_FastDisassembler_nativeDestroy
  (JNIEnv *env, jclass cls, jlong handle) {
  t_capstone capstone = (t_capstone) handle;
  cs_err err = cs_close(&capstone->handle);
  free(capstone);
  return err;
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    setDetail
 * Signature: (JZ)I
 */
JNIEXPORT jint JNICALL Java_capstone_jni_FastDisassembler_setDetail
  (JNIEnv *env, jclass cls, jlong handle, jboolean on) {
  t_capstone capstone = (t_capstone) handle;
  return cs_option(capstone->handle, CS_OPT_DETAIL, on ? CS_OPT_ON : CS_OPT_OFF);
}

static jclass cInstruction = NULL;
static jmethodID mFastInstructionConstructor;
static jclass cArmOpShift = NULL;
static jmethodID mArmOpShiftConstructor;
static jclass cRegsAccess = NULL;
static jmethodID mRegsAccessConstructor;
static jclass cInsnDeallocator = NULL;
static jmethodID mInsnDeallocatorConstructor;

static jclass cArmOpInfo = NULL;
static jmethodID mArmOpInfoConstructor;
static jclass cArmOperand = NULL;
static jmethodID mArmOperandConstructor;
static jclass cArmOpValue = NULL;
static jmethodID mArmOpValueConstructor;
static jclass cArmMemType = NULL;
static jmethodID mArmMemTypeConstructor;

static jclass cArm64OpInfo = NULL;
static jmethodID mArm64OpInfoConstructor;
static jclass cArm64Operand = NULL;
static jmethodID mArm64OperandConstructor;
static jclass cArm64OpValue = NULL;
static jmethodID mArm64OpValueConstructor;
static jclass cArm64MemType = NULL;
static jmethodID mArm64MemTypeConstructor;

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    regsAccess
 * Signature: (JJ)Lcapstone/jni/RegsAccess;
 */
JNIEXPORT jobject JNICALL Java_capstone_jni_FastDisassembler_regsAccess
  (JNIEnv *env, jclass cls, jlong handle, jlong ins) {
  t_capstone capstone = (t_capstone) handle;
  cs_insn *insn = (cs_insn *) ins;

  jobject regsAccess = NULL;
  cs_regs regs_read, regs_write;
  uint8_t regs_read_count = 0, regs_write_count = 0;
  cs_err err = cs_regs_access(capstone->handle, insn, regs_read, &regs_read_count, regs_write, &regs_write_count);
  if(err == CS_ERR_OK) {
    jshortArray regsRead = (*env)->NewShortArray(env, regs_read_count);
    (*env)->SetShortArrayRegion(env, regsRead, 0, regs_read_count, (jshort *) regs_read);
    jshortArray regsWrite = (*env)->NewShortArray(env, regs_write_count);
    (*env)->SetShortArrayRegion(env, regsWrite, 0, regs_write_count, (jshort *) regs_write);
    regsAccess = (*env)->NewObject(env, cRegsAccess, mRegsAccessConstructor, regsRead, regsWrite);
    (*env)->DeleteLocalRef(env, regsRead);
    (*env)->DeleteLocalRef(env, regsWrite);
  }
  return regsAccess;
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    getOpInfo
 * Signature: (JJ)Lcapstone/Capstone/OpInfo;
 */
JNIEXPORT jobject JNICALL Java_capstone_jni_FastDisassembler_getOpInfo
  (JNIEnv *env, jclass cls, jlong handle, jlong ins) {
  t_capstone capstone = (t_capstone) handle;
  cs_insn *insn = (cs_insn *) ins;
  cs_detail *detail = insn->detail;

  if(detail) {
    if(capstone->is64Bit) {
      cs_arm64 arm64 = detail->arm64;
      jobjectArray operands = (*env)->NewObjectArray(env, arm64.op_count, cArm64Operand, NULL);
      for(uint8_t m = 0; m < arm64.op_count; m++) {
        cs_arm64_op arm64_op = arm64.operands[m];
        jobject shift = (*env)->NewObject(env, cArmOpShift, mArmOpShiftConstructor, arm64_op.shift.type, arm64_op.shift.value);
        jobject mem = (*env)->NewObject(env, cArm64MemType, mArm64MemTypeConstructor, arm64_op.mem.base, arm64_op.mem.index, arm64_op.mem.disp);
        jobject value = (*env)->NewObject(env, cArm64OpValue, mArm64OpValueConstructor, arm64_op.reg, arm64_op.imm, arm64_op.fp, mem, arm64_op.pstate, arm64_op.sys, arm64_op.prefetch, arm64_op.barrier);
        jobject op = (*env)->NewObject(env, cArm64Operand, mArm64OperandConstructor, arm64_op.vector_index, arm64_op.vas, 0, shift, arm64_op.ext, arm64_op.type, value, arm64_op.access);
        (*env)->SetObjectArrayElement(env, operands, m, op);
        (*env)->DeleteLocalRef(env, op);
        (*env)->DeleteLocalRef(env, value);
        (*env)->DeleteLocalRef(env, mem);
        (*env)->DeleteLocalRef(env, shift);
      }
      jobject opInfo = (*env)->NewObject(env, cArm64OpInfo, mArm64OpInfoConstructor, arm64.cc, arm64.update_flags, arm64.writeback, operands);
      (*env)->DeleteLocalRef(env, operands);
      return opInfo;
    } else {
      cs_arm arm = detail->arm;
      jobjectArray operands = (*env)->NewObjectArray(env, arm.op_count, cArmOperand, NULL);
      for(uint8_t m = 0; m < arm.op_count; m++) {
        cs_arm_op arm_op = arm.operands[m];
        jobject shift = (*env)->NewObject(env, cArmOpShift, mArmOpShiftConstructor, arm_op.shift.type, arm_op.shift.value);
        jobject mem = (*env)->NewObject(env, cArmMemType, mArmMemTypeConstructor, arm_op.mem.base, arm_op.mem.index, arm_op.mem.scale, arm_op.mem.disp, arm_op.mem.lshift);
        jobject value = (*env)->NewObject(env, cArmOpValue, mArmOpValueConstructor, arm_op.reg, arm_op.imm, arm_op.fp, mem, arm_op.setend);
        jobject op = (*env)->NewObject(env, cArmOperand, mArmOperandConstructor, arm_op.vector_index, shift, arm_op.type, value, arm_op.subtracted);
        (*env)->SetObjectArrayElement(env, operands, m, op);
        (*env)->DeleteLocalRef(env, op);
        (*env)->DeleteLocalRef(env, value);
        (*env)->DeleteLocalRef(env, mem);
        (*env)->DeleteLocalRef(env, shift);
      }
      jobject opInfo = (*env)->NewObject(env, cArmOpInfo, mArmOpInfoConstructor, arm.usermode, arm.vector_size, arm.vector_data, arm.cps_mode, arm.cps_flag, arm.cc, arm.update_flags, arm.writeback, arm.mem_barrier, operands);
      (*env)->DeleteLocalRef(env, operands);
      return opInfo;
    }
  } else {
    return NULL;
  }
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    disasm
 * Signature: (J[BJJ)[Lcapstone/jni/FastInstruction;
 */
JNIEXPORT jobjectArray JNICALL Java_capstone_jni_FastDisassembler_disasm
  (JNIEnv *env, jobject disassembler, jlong handle, jbyteArray bytes, jlong address, jlong count) {
  t_capstone capstone = (t_capstone) handle;

  (*env)->EnsureLocalCapacity(env, 512);

  jbyte *code = (*env)->GetByteArrayElements(env, bytes, NULL);
  jsize size = (*env)->GetArrayLength(env, bytes);

  cs_insn *insn;
  count = cs_disasm(capstone->handle, (uint8_t *) code, size, address, count, &insn);

  jobjectArray array = (*env)->NewObjectArray(env, count, cInstruction, NULL);
  if(count > 0) {
    jobject deallocator = (*env)->NewObject(env, cInsnDeallocator, mInsnDeallocatorConstructor, (jlong) insn, count);
    for(size_t i = 0; i < count; i++) {
      cs_detail *detail = insn[i].detail;

      jstring mnemonic = (*env)->NewStringUTF(env, insn[i].mnemonic);
      jstring opStr = (*env)->NewStringUTF(env, insn[i].op_str);
      jbyteArray _asm = (*env)->NewByteArray(env, insn[i].size);
      (*env)->SetByteArrayRegion(env, _asm, 0, insn[i].size, (jbyte *) insn[i].bytes);

      jobject ins = (*env)->NewObject(env, cInstruction, mFastInstructionConstructor, disassembler, insn[i].id, insn[i].address, mnemonic, opStr, _asm, deallocator, (jlong) &insn[i]);
      (*env)->SetObjectArrayElement(env, array, i, ins);
      (*env)->DeleteLocalRef(env, mnemonic);
      (*env)->DeleteLocalRef(env, opStr);
      (*env)->DeleteLocalRef(env, _asm);
      (*env)->DeleteLocalRef(env, ins);
    }
    (*env)->DeleteLocalRef(env, deallocator);
  }

  (*env)->ReleaseByteArrayElements(env, bytes, code, JNI_ABORT);
  return array;
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    cs_free
 * Signature: (JJ)V
 */
JNIEXPORT void JNICALL Java_capstone_jni_FastDisassembler_cs_1free
  (JNIEnv *env, jclass cls, jlong handle, jlong count) {
  cs_insn *insn = (cs_insn *) handle;
  cs_free(insn, count);
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    regName
 * Signature: (JI)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL Java_capstone_jni_FastDisassembler_regName
  (JNIEnv *env, jclass cls, jlong handle, jint regId) {
  t_capstone capstone = (t_capstone) handle;

  const char* name = cs_reg_name(capstone->handle, regId);
  if(name) {
    jstring str = (*env)->NewStringUTF(env, name);
    return str;
  } else {
    return NULL;
  }
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    mapToUnicornReg
 * Signature: (JI)I
 */
JNIEXPORT jint JNICALL Java_capstone_jni_FastDisassembler_mapToUnicornReg
  (JNIEnv *env, jclass cls, jlong handle, jint regId) {
  t_capstone capstone = (t_capstone) handle;
  return capstone->map2U(regId);
}

/*
 * Class:     capstone_jni_FastDisassembler
 * Method:    mapToCapstoneReg
 * Signature: (JI)I
 */
JNIEXPORT jint JNICALL Java_capstone_jni_FastDisassembler_mapToCapstoneReg
  (JNIEnv *env, jclass cls, jlong handle, jint regId) {
  t_capstone capstone = (t_capstone) handle;
  return capstone->map2C(regId);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    jclass cls;
    JNIEnv *env;
    if (JNI_OK != (*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6)) {
       return JNI_ERR;
    }
    cls = (*env)->FindClass(env, "capstone/jni/FastInstruction");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cInstruction = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/OpShift");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArmOpShift = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/RegsAccess");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cRegsAccess = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/InsnDeallocator");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cInsnDeallocator = (*env)->NewGlobalRef(env, cls);

    mFastInstructionConstructor = (*env)->GetMethodID(env, cInstruction, "<init>", "(Lcapstone/jni/FastDisassembler;IJLjava/lang/String;Ljava/lang/String;[BLcapstone/jni/InsnDeallocator;J)V");
    mArmOpShiftConstructor = (*env)->GetMethodID(env, cArmOpShift, "<init>", "(II)V");
    mRegsAccessConstructor = (*env)->GetMethodID(env, cRegsAccess, "<init>", "([S[S)V");
    mInsnDeallocatorConstructor = (*env)->GetMethodID(env, cInsnDeallocator, "<init>", "(JJ)V");

    cls = (*env)->FindClass(env, "capstone/jni/arm/OpInfo");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArmOpInfo = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm/Operand");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArmOperand = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm/OpValue");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArmOpValue = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm/MemType");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArmMemType = (*env)->NewGlobalRef(env, cls);

    mArmOpInfoConstructor = (*env)->GetMethodID(env, cArmOpInfo, "<init>", "(ZIIIIIZZI[Lcapstone/jni/arm/Operand;)V");
    mArmOperandConstructor = (*env)->GetMethodID(env, cArmOperand, "<init>", "(ILcapstone/jni/OpShift;ILcapstone/jni/arm/OpValue;Z)V");
    mArmOpValueConstructor = (*env)->GetMethodID(env, cArmOpValue, "<init>", "(IIDLcapstone/jni/arm/MemType;I)V");
    mArmMemTypeConstructor = (*env)->GetMethodID(env, cArmMemType, "<init>", "(IIIII)V");


    cls = (*env)->FindClass(env, "capstone/jni/arm64/OpInfo");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArm64OpInfo = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm64/Operand");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArm64Operand = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm64/OpValue");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArm64OpValue = (*env)->NewGlobalRef(env, cls);
    cls = (*env)->FindClass(env, "capstone/jni/arm64/MemType");
    if ((*env)->ExceptionCheck(env)) {
       return JNI_ERR;
    }
    cArm64MemType = (*env)->NewGlobalRef(env, cls);

    mArm64OpInfoConstructor = (*env)->GetMethodID(env, cArm64OpInfo, "<init>", "(IZZ[Lcapstone/jni/arm64/Operand;)V");
    mArm64OperandConstructor = (*env)->GetMethodID(env, cArm64Operand, "<init>", "(IIILcapstone/jni/OpShift;IILcapstone/jni/arm64/OpValue;B)V");
    mArm64OpValueConstructor = (*env)->GetMethodID(env, cArm64OpValue, "<init>", "(IJDLcapstone/jni/arm64/MemType;IIII)V");
    mArm64MemTypeConstructor = (*env)->GetMethodID(env, cArm64MemType, "<init>", "(III)V");

    return JNI_VERSION_1_6;
}