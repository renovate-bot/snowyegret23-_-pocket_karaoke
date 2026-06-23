(function () {
    var requestedSemitones = Number("__PITCH_SEMITONES__");
    if (!isFinite(requestedSemitones)) {
        requestedSemitones = 0;
    }

    function clampPitch(value) {
        return Math.max(-12, Math.min(12, Math.round(value)));
    }

    function PitchShifter(sampleRate) {
        this.sampleRate = sampleRate || 44100;
        this.bufferLength = Math.max(8192, Math.round(this.sampleRate * 2));
        this.buffers = [
            new Float32Array(this.bufferLength),
            new Float32Array(this.bufferLength)
        ];
        this.writeIndex = 0;
        this.phase = 0;
        this.semitones = 0;
        this.ratio = 1;
        this.maxDelaySamples = Math.round(this.sampleRate * 0.09);
        this.minDelaySamples = Math.max(32, Math.round(this.sampleRate * 0.004));
        this.delayRangeSamples = this.maxDelaySamples - this.minDelaySamples;
    }

    PitchShifter.prototype.setPitch = function (semitones) {
        this.semitones = clampPitch(semitones);
        this.ratio = Math.pow(2, this.semitones / 12);
    };

    PitchShifter.prototype.read = function (buffer, index) {
        while (index < 0) {
            index += this.bufferLength;
        }
        while (index >= this.bufferLength) {
            index -= this.bufferLength;
        }
        var index0 = Math.floor(index);
        var index1 = index0 + 1;
        if (index1 >= this.bufferLength) {
            index1 = 0;
        }
        var fraction = index - index0;
        return buffer[index0] * (1 - fraction) + buffer[index1] * fraction;
    };

    PitchShifter.prototype.windowForPhase = function (phase) {
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * phase);
    };

    PitchShifter.prototype.shiftedSample = function (channel, phase) {
        var normalizedPhase = phase - Math.floor(phase);
        var delay;
        if (this.ratio >= 1) {
            delay = this.minDelaySamples + this.delayRangeSamples * (1 - normalizedPhase);
        } else {
            delay = this.minDelaySamples + this.delayRangeSamples * normalizedPhase;
        }
        return this.read(this.buffers[channel], this.writeIndex - delay);
    };

    PitchShifter.prototype.process = function (inputBuffer, outputBuffer) {
        var inputLeft = inputBuffer.numberOfChannels > 0 ? inputBuffer.getChannelData(0) : null;
        var inputRight = inputBuffer.numberOfChannels > 1 ? inputBuffer.getChannelData(1) : inputLeft;
        var outputLeft = outputBuffer.getChannelData(0);
        var outputRight = outputBuffer.numberOfChannels > 1 ? outputBuffer.getChannelData(1) : outputLeft;
        var length = outputBuffer.length;
        var passThrough = Math.abs(this.ratio - 1) < 0.001;
        var phaseStep = Math.abs(1 - this.ratio) / Math.max(1, this.delayRangeSamples);

        for (var i = 0; i < length; i++) {
            var left = inputLeft ? inputLeft[i] : 0;
            var right = inputRight ? inputRight[i] : left;
            this.buffers[0][this.writeIndex] = left;
            this.buffers[1][this.writeIndex] = right;

            if (passThrough) {
                outputLeft[i] = left;
                outputRight[i] = right;
            } else {
                var phaseA = this.phase;
                var phaseB = this.phase + 0.5;
                var windowA = this.windowForPhase(phaseA);
                var windowB = this.windowForPhase(phaseB);
                outputLeft[i] = this.shiftedSample(0, phaseA) * windowA
                    + this.shiftedSample(0, phaseB) * windowB;
                outputRight[i] = this.shiftedSample(1, phaseA) * windowA
                    + this.shiftedSample(1, phaseB) * windowB;
                this.phase += phaseStep;
                if (this.phase >= 1) {
                    this.phase -= Math.floor(this.phase);
                }
            }

            this.writeIndex++;
            if (this.writeIndex >= this.bufferLength) {
                this.writeIndex = 0;
            }
        }
    };

    var existing = window.PocketKaraoke;
    if (existing && existing.setPitch && existing.scan) {
        existing.setPitch(requestedSemitones);
        existing.scan();
        return;
    }

    var state = {
        semitones: clampPitch(requestedSemitones),
        context: null,
        items: [],
        observer: null,
        scanTimer: null
    };

    function getAudioContext() {
        if (!state.context) {
            var AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (!AudioContextClass) {
                return null;
            }
            state.context = new AudioContextClass();
        }
        return state.context;
    }

    function resumeContext() {
        var context = state.context;
        if (context && context.state === "suspended" && context.resume) {
            context.resume();
        }
    }

    function connectMedia(media) {
        if (!media || media.__pocketKaraokeAttached || media.__pocketKaraokeFailed) {
            return;
        }
        if (state.semitones === 0) {
            return;
        }

        var context = getAudioContext();
        if (!context || !context.createMediaElementSource || !context.createScriptProcessor) {
            return;
        }

        try {
            media.__pocketKaraokeAttached = true;
            var source = context.createMediaElementSource(media);
            var processor = context.createScriptProcessor(1024, 2, 2);
            var shifter = new PitchShifter(context.sampleRate);
            shifter.setPitch(state.semitones);

            processor.onaudioprocess = function (event) {
                shifter.process(event.inputBuffer, event.outputBuffer);
            };

            source.connect(processor);
            processor.connect(context.destination);
            state.items.push({
                media: media,
                source: source,
                processor: processor,
                shifter: shifter
            });

            media.addEventListener("play", resumeContext, { passive: true });
            media.addEventListener("playing", resumeContext, { passive: true });
            media.setAttribute("data-pocket-karaoke", "attached");
            resumeContext();
        } catch (error) {
            media.__pocketKaraokeAttached = false;
            media.__pocketKaraokeFailed = true;
            media.setAttribute("data-pocket-karaoke-error", String(error && error.message ? error.message : error));
        }
    }

    function scan() {
        var mediaElements = Array.prototype.slice.call(document.querySelectorAll("audio, video"));
        for (var i = 0; i < mediaElements.length; i++) {
            connectMedia(mediaElements[i]);
        }
    }

    function setPitch(semitones) {
        state.semitones = clampPitch(semitones);
        window.__PocketKaraokePitch = state.semitones;
        for (var i = 0; i < state.items.length; i++) {
            state.items[i].shifter.setPitch(state.semitones);
        }
        if (state.semitones !== 0) {
            scan();
        }
    }

    function installMediaPlayPatch() {
        if (!window.HTMLMediaElement || HTMLMediaElement.prototype.__pocketKaraokePlayPatched) {
            return;
        }
        var originalPlay = HTMLMediaElement.prototype.play;
        HTMLMediaElement.prototype.play = function () {
            connectMedia(this);
            resumeContext();
            return originalPlay.apply(this, arguments);
        };
        HTMLMediaElement.prototype.__pocketKaraokePlayPatched = true;
    }

    function installObserver() {
        if (state.observer || !window.MutationObserver) {
            return;
        }
        state.observer = new MutationObserver(scan);
        state.observer.observe(document.documentElement || document.body, {
            childList: true,
            subtree: true
        });
    }

    document.addEventListener("click", resumeContext, true);
    document.addEventListener("touchstart", resumeContext, true);
    document.addEventListener("keydown", resumeContext, true);

    window.PocketKaraoke = {
        setPitch: setPitch,
        scan: scan
    };

    installMediaPlayPatch();
    installObserver();
    setPitch(state.semitones);
    scan();
    state.scanTimer = window.setInterval(scan, 2000);
})();
