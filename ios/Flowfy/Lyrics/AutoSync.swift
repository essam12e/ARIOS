import Foundation
import AVFoundation

/// On-device automatic lyric timing (port of the Android AutoSync): centre-channel voice-band
/// activity → vocal phrases → dynamic-programming alignment of lines to phrases.
enum AutoSync {
    static let rate = 11025
    static let n = 512
    static let hop = 220
    static let hopSec = Double(hop) / Double(rate)

    struct Pcm { var left: [Float]; var right: [Float]? }

    static func decode(_ url: URL) -> Pcm? {
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let fmt = file.processingFormat
        let ch = Int(fmt.channelCount)
        let sr = fmt.sampleRate
        guard ch > 0, sr > 0, let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: 65536) else { return nil }
        var l: [Float] = [], r: [Float] = []
        l.reserveCapacity(Int(Double(file.length) * Double(rate) / sr) + 16)
        r.reserveCapacity(l.capacity)
        let step = Double(rate) / sr
        var accL: Float = 0, accR: Float = 0, accN: Float = 0, phase = 0.0
        while file.framePosition < file.length {
            do { try file.read(into: buf, frameCount: 65536) } catch { break }
            let frames = Int(buf.frameLength)
            if frames == 0 { break }
            guard let data = buf.floatChannelData else { return nil }
            let a = data[0], b = ch > 1 ? data[1] : data[0]
            for i in 0..<frames {
                accL += a[i]; accR += b[i]; accN += 1
                phase += step
                if phase >= 1 { phase -= 1; l.append(accL / accN); r.append(accR / accN); accL = 0; accR = 0; accN = 0 }
            }
        }
        if l.count < n * 4 { return nil }
        return Pcm(left: l, right: ch > 1 ? r : nil)
    }

    private static func movingAvg(_ x: [Float], _ w: Int) -> [Float] {
        var pre = [Double](repeating: 0, count: x.count + 1)
        for i in 0..<x.count { pre[i + 1] = pre[i] + Double(x[i]) }
        let h = w / 2
        return (0..<x.count).map { i in
            let a = max(0, i - h), b = min(x.count, i + h + 1)
            return Float((pre[b] - pre[a]) / Double(b - a))
        }
    }

    private static func normalise(_ x: [Float]) -> [Float] {
        if x.isEmpty { return x }
        let s = x.sorted()
        let p10 = s[Int(Double(s.count) * 0.10)], p97 = s[Int(Double(s.count - 1) * 0.97)]
        let span = max(p97 - p10, 1e-6)
        return x.map { min(1, max(0, ($0 - p10) / span)) }
    }

    private static var lastPlain: [Float] = []
    private static var lastClean = false

    static func vocalCurve(_ pcm: Pcm) -> [Float] {
        let l = pcm.left, r = pcm.right ?? pcm.left
        var side = 0.0, cnt = 0
        var i = 0
        while i < l.count { let d = Double(l[i] - r[i]); side += d * d; cnt += 1; i += 50 }
        let stereo = pcm.right != nil && side / Double(cnt + 1) > 1e-4
        let frames = max(0, (l.count - n) / hop)
        let win = (0..<n).map { Float(0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(n - 1))) }
        var mr = [Float](repeating: 0, count: n), mi = mr, sr = mr, si = mr
        let lo = Int(250.0 * Double(n) / Double(rate)), hi = Int(3500.0 * Double(n) / Double(rate))
        var env = [Float](repeating: 0, count: frames), tonal = env
        for f in 0..<frames {
            let off = f * hop
            for k in 0..<n {
                let a = l[off + k], b = r[off + k]
                mr[k] = (a + b) * 0.5 * win[k]; mi[k] = 0
                sr[k] = (a - b) * 0.5 * win[k]; si[k] = 0
            }
            fft(&mr, &mi)
            if stereo { fft(&sr, &si) }
            var centre = 0.0, logSum = 0.0, linSum = 0.0, c = 0
            for k in lo...hi {
                let m = Double(mr[k] * mr[k] + mi[k] * mi[k])
                let s = stereo ? Double(sr[k] * sr[k] + si[k] * si[k]) : 0
                centre += max(0, m - 1.6 * s)
                logSum += log(m + 1e-12); linSum += m; c += 1
            }
            let flat = linSum > 0 ? min(1, max(0, exp(logSum / Double(c)) / (linSum / Double(c)))) : 1
            env[f] = Float(log(1 + centre * 1e4))
            tonal[f] = Float(min(1, max(0, (1 - flat) * (1 - flat) * 3)))
        }
        let modWin = max(3, Int(0.6 / hopSec))
        let trend = movingAvg(env, modWin)
        let d = (0..<frames).map { env[$0] - trend[$0] }
        let a3 = movingAvg(d, 3), a9 = movingAvg(d, 9)
        let bp = (0..<frames).map { (a3[$0] - a9[$0]) * (a3[$0] - a9[$0]) }
        let mod = movingAvg(bp, modWin).map { sqrt($0) }
        let en = normalise(env), mn = normalise(mod)
        lastPlain = normalise(movingAvg((0..<frames).map { en[$0] * tonal[$0] }, 7))
        let srt = env.sorted()
        if srt.isEmpty { lastClean = false } else {
            let p15 = srt[min(srt.count - 1, Int(Double(srt.count) * 0.15))], p60 = srt[min(srt.count - 1, Int(Double(srt.count) * 0.60))]
            lastClean = p60 > 0 && p15 / p60 < 0.6
        }
        return normalise(movingAvg((0..<frames).map { (en[$0] * 0.4 + mn[$0] * 0.6) * tonal[$0] }, 7))
    }

    typealias Phrase = (start: Double, end: Double)

    static func phrases(_ curve: [Float], threshold: Float, minGap: Double = 0.30, minLen: Double = 0.30) -> [Phrase] {
        var out: [Phrase] = []
        var start = -1
        for i in 0..<curve.count {
            let on = curve[i] > threshold
            if on && start < 0 { start = i }
            if !on && start >= 0 { out.append((Double(start) * hopSec, Double(i) * hopSec)); start = -1 }
        }
        if start >= 0 { out.append((Double(start) * hopSec, Double(curve.count) * hopSec)) }
        var merged: [Phrase] = []
        for p in out {
            if let last = merged.last, p.start - last.end < minGap { merged[merged.count - 1] = (last.start, p.end) } else { merged.append(p) }
        }
        return merged.filter { $0.end - $0.start >= minLen }
    }

    static func weight(_ line: String) -> Double { Double(max(1, line.filter { $0.isLetter || $0.isNumber }.count)) }

    static func alignScored(_ lines: [String], _ phrases: [Phrase]) -> (starts: [Double], cost: Double)? {
        if lines.isEmpty || phrases.isEmpty { return nil }
        let w = lines.map(weight)
        let dur = phrases.map { $0.end - $0.start }
        var rate = w.reduce(0, +) / max(0.001, dur.reduce(0, +))
        var result: (starts: [Double], cost: Double)? = nil
        let nL = lines.count, m = phrases.count
        var prefixW = [Double](repeating: 0, count: nL + 1)
        for i in 0..<nL { prefixW[i + 1] = prefixW[i] + w[i] }
        for _ in 0..<3 {
            let INF = Double.greatestFiniteMagnitude / 4
            var dp = [[Double]](repeating: [Double](repeating: INF, count: m + 1), count: nL + 1)
            var bk = [[(Int, Int, Int)?]](repeating: [(Int, Int, Int)?](repeating: nil, count: m + 1), count: nL + 1)
            dp[0][0] = 0
            for i in 0...nL {
                for j in 0...m {
                    let cur = dp[i][j]
                    if cur >= INF { continue }
                    if j < m {
                        let c = cur + 0.9 * dur[j] + 0.4
                        if c < dp[i][j + 1] { dp[i][j + 1] = c; bk[i][j + 1] = (i, j, 0) }
                    }
                    if i == nL { continue }
                    for k in (i + 1)...min(nL, i + 3) {
                        for l in (j + 1)...max(j + 1, min(m, j + 4)) where l <= m {
                            if k - i > 1 && l - j > 1 { continue }
                            let letters = prefixW[k] - prefixW[i]
                            let span = phrases[l - 1].end - phrases[j].start
                            let expect = letters / rate
                            var c = (expect - span) * (expect - span) / max(span, 0.6)
                            if l - 1 > j { for q in j..<(l - 1) { c += max(0, phrases[q + 1].start - phrases[q].end - 0.8) * 0.8 } }
                            c += cur + 0.05 * Double((k - i) + (l - j))
                            if c < dp[k][l] { dp[k][l] = c; bk[k][l] = (i, j, 1) }
                        }
                    }
                }
            }
            var bestJ = m, best = INF
            for j in 0...m {
                var tail = 0.0
                if j < m { for t in j..<m { tail += 0.9 * dur[t] + 0.4 } }
                let c = dp[nL][j] + tail
                if c < best { best = c; bestJ = j }
            }
            if best >= INF { return result }
            var starts = [Double](repeating: 0, count: nL)
            var i = nL, j = bestJ
            var usedLetters = 0.0, usedTime = 0.0
            while i > 0 || j > 0 {
                guard let b = bk[i][j] else { break }
                let (pi, pj, kind) = b
                if kind == 1 {
                    let t0 = phrases[pj].start, t1 = phrases[j - 1].end
                    let total = prefixW[i] - prefixW[pi]
                    usedLetters += total; usedTime += t1 - t0
                    var acc = 0.0
                    for q in pi..<i { starts[q] = t0 + (t1 - t0) * acc / total; acc += w[q] }
                }
                i = pi; j = pj
            }
            result = (starts, best / Double(nL))
            if usedTime > 0 { rate = usedLetters / usedTime }
        }
        return result
    }

    static func toLrc(_ lines: [String], _ starts: [Double]) -> String {
        zip(lines, starts).map { LrcWriter.stamp(Int64($1 * 1000)) + $0 }.joined(separator: "\n") + "\n"
    }

    static func syncPcm(_ pcm: Pcm, _ lines: [String]) -> String? {
        let curve = vocalCurve(pcm)
        let thresholds: [Float] = [0.10, 0.14, 0.18, 0.22, 0.27, 0.33, 0.40]
        var best: (starts: [Double], cost: Double)?
        if lastClean {
            let target = Double(lines.count) * 1.4
            let ps = thresholds.map { phrases(lastPlain, threshold: $0) }.filter { !$0.isEmpty }
            if let p = ps.min(by: { score($0, target, lines.count) < score($1, target, lines.count) }) { best = alignScored(lines, p) }
        } else {
            best = thresholds.map { phrases(curve, threshold: $0) }
                .filter { $0.count >= max(1, lines.count / 2) }
                .compactMap { alignScored(lines, $0) }
                .min { $0.cost < $1.cost }
        }
        guard let best else { return nil }
        return toLrc(lines, best.starts)
    }

    private static func score(_ p: [Phrase], _ target: Double, _ n: Int) -> Double {
        abs(Double(p.count) - target) + (p.count < n / 2 ? 100 : 0)
    }

    static func sync(url: URL, lines: [String]) -> String? {
        guard lines.count >= 2, let pcm = decode(url) else { return nil }
        return syncPcm(pcm, lines)
    }

    private static func fft(_ re: inout [Float], _ im: inout [Float]) {
        let n = re.count
        var j = 0
        for i in 1..<n {
            var bit = n >> 1
            while j & bit != 0 { j ^= bit; bit >>= 1 }
            j ^= bit
            if i < j { re.swapAt(i, j); im.swapAt(i, j) }
        }
        var len = 2
        while len <= n {
            let ang = -2 * Double.pi / Double(len)
            let wr = Float(cos(ang)), wi = Float(sin(ang))
            var i = 0
            let half = len / 2
            while i < n {
                var cr: Float = 1, ci: Float = 0
                for k in 0..<half {
                    let xr = re[i + k + half] * cr - im[i + k + half] * ci
                    let xi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k + half] = re[i + k] - xr; im[i + k + half] = im[i + k] - xi
                    re[i + k] += xr; im[i + k] += xi
                    let nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len <<= 1
        }
    }
}
