"""Static safeguards for the DPKS-only synthetic ONNX isolated worker.

These checks do not replace merged-manifest, JVM or real-device testing.
"""
from pathlib import Path
import unittest
from xml.etree import ElementTree as ET


APP = Path(__file__).resolve().parents[1] / "third_party/markor/app/src"
NS = "{http://schemas.android.com/apk/res/android}"


class OnnxIsolatedWorkerContract(unittest.TestCase):
    def test_dpks_only_service_has_isolated_uid_and_no_export(self):
        manifest = ET.parse(APP / "flavorAtest/AndroidManifest.xml").getroot()
        services = list(manifest.iter("service"))
        self.assertEqual(1, len(services))
        self.assertEqual(".knowledge.KnowledgeOnnxIsolatedService",
                         services[0].get(NS + "name"))
        self.assertEqual("false", services[0].get(NS + "exported"))
        self.assertEqual("true", services[0].get(NS + "isolatedProcess"))
        self.assertEqual(":dpks_onnx_cpu", services[0].get(NS + "process"))
        for flavor in ("flavorDefault", "flavorGplay"):
            plain_manifest = APP / flavor / "AndroidManifest.xml"
            if plain_manifest.is_file():
                self.assertFalse(any("KnowledgeOnnxIsolatedService" in
                                     item.get(NS + "name", "")
                                     for item in ET.parse(plain_manifest).iter("service")))
            client = (APP / flavor / "java/net/gsantner/markor/knowledge/"
                      "KnowledgeOnnxIsolatedClient.java").read_text()
            self.assertIn("return false;", client)

    def test_ipc_is_scalar_only_with_cancellation_and_both_deadlines(self):
        base = "java/net/gsantner/markor/knowledge/"
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        self.assertIn("WATCHDOG_MILLIS = 30_000L", protocol)
        self.assertIn("handler.postDelayed(deadline", service)
        self.assertIn("Process.killProcess(Process.myPid())", service)
        self.assertIn("KnowledgeOnnxProcessIdentity.isWorker()", service)
        self.assertIn("KnowledgeOnnxCpuBackend.smoke(this, memory)", service)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        self.assertIn("KnowledgeOnnxCpuBackend.inspect(model, memory)", service)
        self.assertIn("Arrays.fill(model, (byte) 0)", service)
        self.assertIn("mainHandler.postDelayed(clientDeadline", client)
        self.assertIn("response.arg1 != activeRequestId", client)
        self.assertIn("CANCEL_AND_EXIT", client)
        self.assertIn("onnxSmokeClient.start(", activity)
        self.assertNotIn("final KnowledgeOnnxSmoke.Report report = KnowledgeOnnxCpuBackend.smoke()", activity)

    def test_384_e5_synthetic_run_is_inside_isolated_uid_and_never_opens_weight_paths(self):
        base = "java/net/gsantner/markor/knowledge/"
        backend = (APP / "flavorAtest" / base / "KnowledgeOnnxCpuBackend.java").read_text()
        smoke = (APP / "flavorAtest" / base / "KnowledgeE5IsolatedSmoke.java").read_text()
        suite = (APP / "androidTest" / base /
                 "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("KnowledgeE5IsolatedSmoke.verify(workerContext, environment, options, memory)",
                      backend)
        self.assertIn("KnowledgeOnnxProcessIdentity.isWorker()", smoke)
        self.assertIn("KnowledgeOnnxWorkerMemory.requireAvailable(context, false)", smoke)
        self.assertIn("KnowledgeOnnxWorkerMemory.snapshot(context)", smoke)
        self.assertIn("KnowledgeE5ResourceEstimate.assess(", smoke)
        self.assertIn("KnowledgeE5TokenEmbeddingSmoke.graph(", smoke)
        self.assertIn("KnowledgeE5OrtTensorRunner.run(", smoke)
        self.assertIn("KnowledgeE5TokenEmbeddingSmoke.expectedVector(batch)", smoke)
        self.assertIn("Arrays.fill(syntheticModel, (byte) 0)", smoke)
        self.assertIn("identityAnd384E5SmokesCanRunTwiceAcrossWorkerLifecycle", suite)
        self.assertNotIn("KnowledgeModelPackageStore", smoke)
        self.assertNotIn("model.onnx", smoke)

    def test_debug_fd_e5_is_pinned_before_native_run_and_has_scalar_only_reply(self):
        base = "java/net/gsantner/markor/knowledge/"
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
        backend = (APP / "flavorAtest" / base / "KnowledgeOnnxCpuBackend.java").read_text()
        smoke = (APP / "flavorAtest" / base / "KnowledgeE5IsolatedSmoke.java").read_text()
        tests = (APP / "androidTest" / base /
                 "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("RUN_DEBUG_E5_FD = 12", protocol)
        self.assertIn("DEBUG_E5_TWO_INPUT_POOLER = 1", protocol)
        self.assertIn("DEBUG_E5_THREE_INPUT = 2", protocol)
        self.assertIn("validDebugE5Variant(final int variant)", protocol)
        self.assertIn("final boolean debugE5Fd = BuildConfig.DEBUG", service)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        self.assertIn("KnowledgeOnnxIsolatedProtocol.validDebugE5Variant(debugVariant)", service)
        self.assertIn("KnowledgeOnnxCpuBackend.runPinnedSyntheticE5(this, model,", service)
        self.assertIn("Arrays.fill(model, (byte) 0)", service)
        self.assertIn("startDebugSyntheticE5(", client)
        self.assertIn("if (!BuildConfig.DEBUG || !KnowledgeOnnxIsolatedProtocol.validDebugE5Variant(variant))", client)
        self.assertIn("data.putInt(KnowledgeOnnxIsolatedProtocol.DEBUG_E5_VARIANT, debugVariant)", client)
        self.assertIn("if (!BuildConfig.DEBUG || !KnowledgeOnnxProcessIdentity.isWorker())", backend)
        self.assertLess(backend.index("KnowledgeE5IsolatedSmoke.requirePinnedGraph(received, variant)"),
                        backend.index("final OrtEnvironment environment = OrtEnvironment.getEnvironment()"))
        self.assertIn("KnowledgeE5IsolatedSmoke.verifyTransported(", backend)
        self.assertLess(backend.index("KnowledgeE5IsolatedSmoke.verifyTransported("),
                        backend.index("return inspect(received, memory)"))
        self.assertIn("KnowledgeE5TokenEmbeddingSmoke.graph(6,", smoke)
        self.assertIn("tokenTypes, !tokenTypes", smoke)
        self.assertIn("!Arrays.equals(pinned, received)", smoke)
        self.assertLess(smoke.index("requirePinnedGraph(received, variant);"),
                        smoke.index("verifyGraph(workerContext, environment, options, memory, profile, batch,"))
        self.assertIn("Arrays.fill(pinned, (byte) 0)", smoke)
        self.assertIn("debugFdPinnedE5RunsAll384ChannelsInsideWorker", tests)
        self.assertIn("debugFdPinnedThreeInputE5RunsAll384ChannelsInsideWorker", tests)
        self.assertIn("debugFdE5RejectsOtherValidGraphWithItsOwnCorrectSha", tests)
        self.assertIn("debugFdE5RejectsMutatedSelfHashedFixtureAndInvalidVariant", tests)
        self.assertIn("debugFdWorkerRejectsUnknownVariantWithoutReadingItsPipe", tests)
        self.assertNotIn("KnowledgeModelPackageStore", smoke)

    def test_isolated_process_identity_uses_no_forbidden_activity_manager_call(self):
        folder = APP / "main/java/net/gsantner/markor"
        application = (folder / "ApplicationObject.java").read_text()
        identity = (folder / "knowledge/KnowledgeOnnxProcessIdentity.java").read_text()
        service = (APP / "flavorAtest/java/net/gsantner/markor/knowledge/"
                   "KnowledgeOnnxIsolatedService.java").read_text()
        self.assertNotIn("getMyMemoryState", application)
        self.assertNotIn("getMyMemoryState", identity)
        self.assertNotIn("getMyMemoryState", service)
        self.assertIn("Application.getProcessName()", identity)
        self.assertIn("Build.VERSION.SDK_INT >= 34", identity)
        self.assertIn("Process.isIsolated()", identity)
        self.assertIn("Process.isIsolatedUid(Process.myUid())", identity)
        self.assertIn('new FileInputStream("/proc/self/cmdline")', identity)
        self.assertIn('process.startsWith(PACKAGE_PREFIX + ":")', identity)

    def test_real_session_uses_fd_not_model_path_or_binder_payload(self):
        base = "java/net/gsantner/markor/knowledge/"
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        self.assertIn("ParcelFileDescriptor.createPipe()", client)
        self.assertIn("data.putParcelable(KnowledgeOnnxIsolatedProtocol.PIPE_FD, readPipe)", client)
        self.assertIn("Math.min(64 * 1024, bytes.length - offset)", client)
        self.assertIn("KnowledgeOnnxSessionPipe.validSha256(expectedSha256)", client)
        self.assertIn("onnxSessionClient.start(bytes, entry.sha256", activity)
        self.assertNotIn("KnowledgeOnnxCpuBackend.inspect(bytes)", activity)
        self.assertIn("KnowledgeOnnxSessionPipe.readVerified(stream, size, sha)", service)
        for flavor in ("flavorDefault", "flavorGplay"):
            shim = (APP / flavor / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
            self.assertIn("return false;", shim)

    def test_worker_death_and_failed_binding_fail_closed_without_stale_callbacks(self):
        base = "java/net/gsantner/markor/knowledge/"
        for client_name in ("KnowledgeOnnxIsolatedClient.java",
                            "KnowledgeOnnxIsolatedSessionClient.java"):
            client = (APP / "flavorAtest" / base / client_name).read_text()
            self.assertIn("service.linkToDeath(died, 0)", client)
            self.assertIn("activeBinder.unlinkToDeath(binderDeath, 0)", client)
            self.assertIn("if (connection == requestConnection) notifyFailure()", client)
            self.assertIn("onBindingDied(final ComponentName name)", client)
            self.assertIn("onNullBinding(final ComponentName name)", client)
            self.assertIn("if (connection == this) notifyFailure()", client)
        emulator = (APP / "androidTest/java/net/gsantner/markor/knowledge/"
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("Manual retry after cancel failed to bind", emulator)
        self.assertIn("Late cancelled request invoked a callback", emulator)
        self.assertIn("Parallel session unexpectedly bound", emulator)
        self.assertIn("Rejected weights not wiped", emulator)

    def test_debug_only_bind_timeout_retry_contract(self):
        base = "java/net/gsantner/markor/knowledge/"
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        self.assertIn("CLIENT_TIMEOUT_MILLIS = WATCHDOG_MILLIS + 5_000L", protocol)
        for name in ("KnowledgeOnnxIsolatedClient.java",
                     "KnowledgeOnnxIsolatedSessionClient.java"):
            source = (APP / "flavorAtest" / base / name).read_text()
            self.assertIn("BuildConfig.DEBUG && debugTimeoutMillis >= 250L", source)
            self.assertIn("debugTimeoutMillis < KnowledgeOnnxIsolatedProtocol.CLIENT_TIMEOUT_MILLIS", source)
            self.assertIn("restoreDefaultTimeoutForDebugTest()", source)
            self.assertIn("mainHandler.postDelayed(", source)
            self.assertIn("final Callback done = callback;\n        cancel();", source)
        emulator = (APP / "androidTest/java/net/gsantner/markor/knowledge/"
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("syntheticBindTimeoutFailsOnceAndFreshRequestCanReconnect", emulator)
        self.assertIn("fdBindTimeoutWipesBytesAndFreshRequestCanReconnect", emulator)
        self.assertIn("class DelayedBindContext extends ContextWrapper", emulator)
        self.assertIn("Timed-out weights not wiped", emulator)
        self.assertIn("Expired FD callback ran again", emulator)

    def test_debug_simulated_oom_exits_only_worker_without_reply_or_large_allocation(self):
        base = "java/net/gsantner/markor/knowledge/"
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        emulator = (APP / "androidTest" / base /
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("DEBUG_SIMULATE_OOM = 11", protocol)
        self.assertIn("debugOom && !BuildConfig.DEBUG", service)
        self.assertIn('throw new OutOfMemoryError("debug-only simulated worker OOM")', service)
        self.assertIn("catch (OutOfMemoryError exhausted)", service)
        self.assertIn("fatalMemory = true;\n                exitSandboxProcess();", service)
        self.assertIn("if (!fatalMemory) {", service)
        self.assertIn("simulatedWorkerOomKillsBinderAndFreshManualRunSucceeds", emulator)
        self.assertIn("connection.died.await(5, TimeUnit.SECONDS)", emulator)
        self.assertIn("Fatal OOM unexpectedly sent an ONNX result", emulator)
        self.assertIn("Fresh manual run invoked multiple callbacks", emulator)
        # The OOM regression must never allocate huge arrays to provoke a crash.
        # Other FD tests may legitimately use a fixed 64 KiB streaming buffer.
        oom_test = emulator.split(
            "public void simulatedWorkerOomKillsBinderAndFreshManualRunSucceeds()", 1
        )[1].split("@Test public void", 1)[0]
        self.assertNotIn("new byte[", oom_test)

    def test_native_onnx_io_summary_is_bounded_scalar_only_and_name_free(self):
        base = "java/net/gsantner/markor/knowledge/"
        summary = (APP / "main" / base / "KnowledgeOnnxIoSummary.java").read_text()
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        backend = (APP / "flavorAtest" / base / "KnowledgeOnnxCpuBackend.java").read_text()
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedSessionClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        emulator = (APP / "androidTest" / base /
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("MAX_IO = 32", summary)
        self.assertIn("MAX_RANK = 16", summary)
        self.assertIn("info.floatInputs + info.int64Inputs > info.inputTensors", summary)
        self.assertIn("info.dynamicInputAxes <= info.inputTensors * info.maxInputRank", summary)
        self.assertIn("session.getInputInfo()", backend)
        self.assertIn("session.getOutputInfo()", backend)
        self.assertIn("tensor.getShape()", backend)
        self.assertIn("tensor.type == OnnxJavaType.FLOAT", backend)
        self.assertNotIn("getDimensionNames()", backend)
        self.assertNotIn("node.getName()", backend)
        self.assertIn("KnowledgeOnnxIoSummary.valid(summary, inputs, outputs)", backend)
        self.assertIn("KnowledgeOnnxIoSummary.valid(io, inputs, outputs)", client)
        self.assertIn("KnowledgeOnnxIoSummary.valid(", service)
        self.assertIn("data.putInt(KnowledgeOnnxIsolatedProtocol.INPUT_TENSORS", service)
        self.assertIn("formatSessionDiagnostics(report)", activity)
        self.assertIn("Native ONNX I/O summary failed its IPC bounds", emulator)
        for key in ("INPUT_TENSORS", "FLOAT_INPUTS", "INT64_INPUTS",
                    "DYNAMIC_INPUT_AXES", "MAX_INPUT_RANK"):
            self.assertIn(key, protocol)

    def test_source_native_io_count_comparison_uses_the_verified_preflight_snapshot(self):
        base = "java/net/gsantner/markor/knowledge/"
        store = (APP / "main" / base / "KnowledgeModelPackageStore.java").read_text()
        agreement = (APP / "main" / base / "KnowledgeOnnxIoAgreement.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        test = (APP / "test" / base / "KnowledgeOnnxIoAgreementTest.java").read_text()
        emulator = (APP / "androidTest" / base /
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("return readForCpuSessionWithAudit(entry).bytes;", store)
        self.assertIn("final Audit audit = audit(entry, null)", store)
        self.assertIn("new CpuSessionInput(bytes, audit.container,", store)
        self.assertIn("optionalE5DescriptorSha256(entry, manifest, audit.container)", store)
        self.assertIn("verified = true;\n            return input;", store)
        self.assertIn("readForCpuSessionWithAudit(entry)", activity)
        self.assertIn("formatIoAgreement(prepared, report)", activity)
        self.assertIn("formatE5DescriptorAgreement(prepared, report)", activity)
        self.assertIn("KnowledgeOnnxIoAgreement.compare(", activity)
        self.assertIn("COUNTS_AGREE_TYPES_PARTIAL", agreement)
        self.assertIn("INPUT_OUTPUT_COUNTS_DIFFER", agreement)
        self.assertIn("TENSOR_COUNTS_DIFFER", agreement)
        self.assertIn("KNOWN_TYPE_COUNTS_DIFFER", agreement)
        self.assertIn("floatInputs == runtimeSummary.floatInputs", agreement)
        self.assertIn("int64Outputs == runtimeSummary.int64Outputs", agreement)
        self.assertIn("container.floatInputs", store)
        self.assertIn("container.int64Outputs", store)
        self.assertIn("initializer-backed inputs", agreement.lower())
        self.assertNotIn("getName()", agreement)
        self.assertIn("differentInputCountIsWarningNotRejectionOfSession", test)
        self.assertIn("incompleteTypeDeclarationsRemainUnknown", test)
        self.assertIn("KnowledgeModelContainerProbe.inspect(", emulator)
        self.assertIn("Offline GraphProto and native I/O counts diverged for Identity", emulator)

    def test_live_ram_gate_is_rechecked_after_model_sha_audit(self):
        base = "java/net/gsantner/markor/knowledge/"
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        gate = (APP / "main" / base / "KnowledgeOnnxSessionGate.java").read_text()
        # Declaration + check before file reading + check immediately before Binder/FD.
        self.assertEqual(3, activity.count("requireAvailableOnnxMemory()"))
        self.assertIn("KnowledgeOnnxSessionGate.requireAvailableMemory(", activity)
        self.assertIn("if (lowMemory || availableBytes < MIN_AVAILABLE_RAM_BYTES)", gate)
        self.assertIn("Arrays.fill(bytes, (byte) 0);\n                        modelImportActive = false;", activity)

    def test_worker_pss_and_last_moment_low_memory_gate_are_isolated(self):
        base = "java/net/gsantner/markor/knowledge/"
        service = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedService.java").read_text()
        backend = (APP / "flavorAtest" / base / "KnowledgeOnnxCpuBackend.java").read_text()
        sample = (APP / "flavorAtest" / base / "KnowledgeOnnxWorkerMemory.java").read_text()
        protocol = (APP / "main" / base / "KnowledgeOnnxIsolatedProtocol.java").read_text()
        emulator = (APP / "androidTest" / base /
                    "KnowledgeOnnxIsolatedLifecycleInstrumentedTest.java").read_text()
        self.assertIn("Debug.getPss()", sample)
        self.assertIn("manager.getMemoryInfo(memory)", sample)
        self.assertIn("KnowledgeOnnxSessionGate.requireAvailableMemory(", sample)
        self.assertIn("KnowledgeOnnxWorkerMemory.requireAvailable(this, false)", service)
        self.assertIn("KnowledgeOnnxWorkerMemory.requireAvailable(this, debugLowMemory)", service)
        self.assertIn("BuildConfig.DEBUG && request.getBoolean(", service)
        self.assertIn("KnowledgeOnnxIsolatedProtocol.RESULT_SESSION_MEMORY_DENIED", service)
        self.assertIn("Arrays.fill(model, (byte) 0)", service)
        self.assertIn("memory.observe(KnowledgeOnnxWorkerMemory.samplePssKiB())", backend)
        self.assertIn("memory.complete(KnowledgeOnnxWorkerMemory.samplePssKiB())", backend)
        self.assertIn("putMemory(data, result.workerMemory, result.workerRssHighWaterKiB)", service)
        self.assertIn("verifiedFdIsRefusedByDebugWorkerLowRamGate", emulator)
        self.assertIn("assertWorkerPssSnapshots", emulator)
        self.assertIn('PSS_OBSERVED_KIB = "pss_observed_kib"', protocol)
        rss = (APP / "main" / base / "KnowledgeOnnxRssHighWater.java").read_text()
        client = (APP / "flavorAtest" / base / "KnowledgeOnnxIsolatedClient.java").read_text()
        fd_client = (APP / "flavorAtest" / base /
                     "KnowledgeOnnxIsolatedSessionClient.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        self.assertIn('new FileReader("/proc/self/status")', rss)
        self.assertIn('line.startsWith("VmHWM:")', rss)
        self.assertIn("catch (IOException | RuntimeException unavailable)", rss)
        self.assertIn("KnowledgeOnnxRssHighWater.readSelf()", backend)
        self.assertIn('RSS_HIGH_WATER_KIB = "rss_hwm_kib"', protocol)
        self.assertIn("KnowledgeOnnxRssHighWater.valid(rssHighWater)", client)
        self.assertIn("KnowledgeOnnxRssHighWater.valid(rssHighWater)", fd_client)
        self.assertIn("formatWorkerMemory(report.workerMemory, report.workerRssHighWaterKiB)",
                      activity)
        self.assertIn("Worker kernel RSS high-water is not bounded", emulator)

    def test_manual_fd_smoke_reuses_generated_graph_and_never_selects_user_model(self):
        base = "java/net/gsantner/markor/knowledge/"
        dialog = (APP / "main" / base / "KnowledgeModelSettingsDialog.java").read_text()
        activity = (APP / "main" / base / "KnowledgeHubActivity.java").read_text()
        pipe = (APP / "main" / base / "KnowledgeOnnxSessionPipe.java").read_text()
        self.assertIn(".setTitle(R.string.knowledge_models_onnx_fd_smoke)", dialog)
        self.assertIn(".setMessage(R.string.knowledge_models_onnx_fd_warning)", dialog)
        self.assertIn("(dialog, which) -> fdSmokeTester.run()", dialog)
        self.assertIn("final byte[] fixture = KnowledgeOnnxSmoke.identityModel()", activity)
        self.assertIn("KnowledgeOnnxSessionPipe.sha256Hex(fixture)", activity)
        self.assertIn("onnxSessionClient.start(fixture, digest", activity)
        self.assertIn("Arrays.fill(fixture, (byte) 0)", activity)
        self.assertIn("static String sha256Hex(final byte[] bytes)", pipe)


if __name__ == "__main__":
    unittest.main()
