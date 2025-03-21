// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.debugger.core.stepping

import com.intellij.debugger.engine.DebugProcess.JAVA_STRATUM
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.MethodFilter
import com.intellij.debugger.engine.RequestHint
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.evaluation.EvaluateException
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.openapi.diagnostic.Logger
import com.sun.jdi.Location
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.request.StepRequest
import org.jetbrains.kotlin.idea.debugger.base.util.safeLineNumber
import org.jetbrains.kotlin.idea.debugger.base.util.safeLocation
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.isKotlinFakeLineNumber
import org.jetbrains.kotlin.idea.debugger.core.isOnSuspensionPoint
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.org.objectweb.asm.Type

open class KotlinRequestHint(
    stepThread: ThreadReferenceProxyImpl,
    suspendContext: SuspendContextImpl,
    stepSize: Int,
    depth: Int,
    filter: MethodFilter?,
    parentHint: RequestHint?
) : RequestHint(stepThread, suspendContext, stepSize, depth, filter, parentHint) {
    private val myInlineFilter = createKotlinInlineFilter(suspendContext)
    override fun isTheSameFrame(context: SuspendContextImpl) =
        super.isTheSameFrame(context) && (myInlineFilter === null || !myInlineFilter.isNestedInline(context))

    override fun doStep(debugProcess: DebugProcessImpl, suspendContext: SuspendContextImpl?, stepThread: ThreadReferenceProxyImpl?, size: Int, depth: Int) {
        if (depth == StepRequest.STEP_OUT) {
            val frameProxy = suspendContext?.frameProxy
            val location = frameProxy?.safeLocation()
            if (location !== null) {
                val action = getStepOutAction(location, frameProxy)
                if (action !== KotlinStepAction.StepOut) {
                    val command = action.createCommand(debugProcess, suspendContext, false)
                    val hint = command.getHint(suspendContext, stepThread, this)!!
                    command.step(suspendContext, stepThread, hint)
                    return
                }
            }
        }
        super.doStep(debugProcess, suspendContext, stepThread, size, depth)
    }
}

// Originally copied from RequestHint
class KotlinStepOverRequestHint(
    stepThread: ThreadReferenceProxyImpl,
    suspendContext: SuspendContextImpl,
    private val filter: KotlinMethodFilter,
    parentHint: RequestHint?,
    stepSize: Int
) : RequestHint(stepThread, suspendContext, stepSize, StepRequest.STEP_OVER, filter, parentHint) {
    private companion object {
        private val LOG = Logger.getInstance(KotlinStepOverRequestHint::class.java)
    }

    private class LocationData(val method: String, val signature: Type, val declaringType: String) {
        companion object {
            fun create(location: Location?): LocationData? {
                val method = location?.safeMethod() ?: return null
                val signature = Type.getMethodType(method.signature())
                return LocationData(method.name(), signature, location.declaringType().name())
            }
        }
    }

    private val startLocation = LocationData.create(suspendContext.getLocationCompat())

    override fun getNextStepDepth(context: SuspendContextImpl): Int {
        try {
            val frameProxy = context.frameProxy ?: return STOP
            if (isTheSameFrame(context)) {
                if (frameProxy.isOnSuspensionPoint()) {
                    // Coroutine will sleep now so we can't continue stepping.
                    // Let's put a run-to-cursor breakpoint and resume the debugger.
                    return if (!installCoroutineResumedBreakpoint(context)) STOP else RESUME
                }

                val location = frameProxy.safeLocation()
                val isAcceptable = location != null && filter.locationMatches(context, location)
                return if (isAcceptable) STOP else StepRequest.STEP_OVER
            } else if (isSteppedOut) {
                val location = frameProxy.safeLocation()

                processSteppingFilters(context, location)?.let { return it }

                val method = location?.safeMethod()
                if (method != null && method.isSyntheticMethodForDefaultParameters() &&
                    isSteppedFromDefaultParamsOriginal(location)) {
                    return StepRequest.STEP_OVER
                }

                val lineNumber = location?.safeLineNumber(JAVA_STRATUM) ?: -1
                return if (lineNumber >= 0) STOP else StepRequest.STEP_OVER
            }
            return StepRequest.STEP_OUT
        } catch (ignored: VMDisconnectedException) {
        } catch (e: EvaluateException) {
            LOG.error(e)
        }

        return STOP
    }

    private fun isSteppedFromDefaultParamsOriginal(location: Location): Boolean {
        val startLocation = this.startLocation ?: return false
        val endLocation = LocationData.create(location) ?: return false

        if (startLocation.declaringType != endLocation.declaringType) {
            return false
        }

        if (startLocation.method + JvmAbi.DEFAULT_PARAMS_IMPL_SUFFIX != endLocation.method) {
            return false
        }

        val startArgs = startLocation.signature.argumentTypes
        val endArgs = endLocation.signature.argumentTypes

        if (startArgs.size >= endArgs.size) {
            // Default params function should always have at least one additional flag parameter
            return false
        }

        for ((index, type) in startArgs.withIndex()) {
            if (endArgs[index] != type) {
                return false
            }
        }

        for (index in startArgs.size until (endArgs.size - 1)) {
            if (endArgs[index].sort != Type.INT) {
                return false
            }
        }

        if (endArgs[endArgs.size - 1].descriptor != "Ljava/lang/Object;") {
            return false
        }

        return true
    }

    private fun installCoroutineResumedBreakpoint(context: SuspendContextImpl): Boolean {
        val frameProxy = context.frameProxy ?: return false
        val location = frameProxy.safeLocation() ?: return false
        val method = location.safeMethod() ?: return false

        context.debugProcess.cancelRunToCursorBreakpoint()
        return CoroutineBreakpointFacility.installCoroutineResumedBreakpoint(context, location, method)
    }
}

interface StopOnReachedMethodFilter

class KotlinStepIntoRequestHint(
    stepThread: ThreadReferenceProxyImpl,
    suspendContext: SuspendContextImpl,
    filter: MethodFilter?,
    parentHint: RequestHint?
) : KotlinRequestHint(stepThread, suspendContext, StepRequest.STEP_LINE, StepRequest.STEP_INTO, filter, parentHint) {
    private var lastWasKotlinFakeLineNumber = false

    private companion object {
        private val LOG = Logger.getInstance(KotlinStepIntoRequestHint::class.java)
    }

    override fun getNextStepDepth(context: SuspendContextImpl): Int {
        try {
            val frameProxy = context.frameProxy ?: return STOP
            val location = frameProxy.safeLocation()
            // Continue stepping into if we are at a compiler generated fake line number.
            if (location != null && isKotlinFakeLineNumber(location)) {
                lastWasKotlinFakeLineNumber = true
                return StepRequest.STEP_INTO
            }
            // If the last line was a fake line number, and we are not smart-stepping,
            // the next non-fake line number is always of interest (otherwise, we wouldn't
            // have had to insert the fake line number in the first place).
            if (lastWasKotlinFakeLineNumber && methodFilter == null) {
                lastWasKotlinFakeLineNumber = false
                return STOP
            }

            val filter = methodFilter
            if (filter is StopOnReachedMethodFilter && filter.locationMatches(context.debugProcess, location)) {
                return STOP
            }

            return super.getNextStepDepth(context)
        } catch (ignored: VMDisconnectedException) {
        } catch (e: EvaluateException) {
            LOG.error(e)
        }
        return STOP
    }
}
