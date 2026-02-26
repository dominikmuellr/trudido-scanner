/*
 * TrudidoScannerSDK
 * Copyright (C) 2026 Dominik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <jni.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <android/log.h>
#include <vector>
#include <algorithm>
#include <cmath>
#include <numeric>

#define TAG "DocScanner"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

// ===================================================================
// Document scanner — edge-support scoring pipeline.
//
// Key insight: the BEST rectangle is NOT the biggest one.
// It's the one whose 4 edges have the strongest, most consistent
// gradient support in the original image.  A real document edge
// shows a clear brightness/colour change; a false-positive
// contour from thresholding artifacts does not.
// ===================================================================

// --- geometry helpers ---------------------------------------------

static double angleCos(const cv::Point& p1, const cv::Point& p2,
                       const cv::Point& p0) {
    double dx1 = p1.x - p0.x, dy1 = p1.y - p0.y;
    double dx2 = p2.x - p0.x, dy2 = p2.y - p0.y;
    return (dx1 * dx2 + dy1 * dy2)
         / std::sqrt((dx1*dx1 + dy1*dy1) * (dx2*dx2 + dy2*dy2) + 1e-10);
}

static void orderPoints(std::vector<cv::Point>& pts) {
    std::vector<cv::Point> o(4);
    std::vector<int> s(4), d(4);
    for (int i = 0; i < 4; i++) {
        s[i] = pts[i].x + pts[i].y;
        d[i] = pts[i].y - pts[i].x;
    }
    o[0] = pts[std::min_element(s.begin(), s.end()) - s.begin()];
    o[2] = pts[std::max_element(s.begin(), s.end()) - s.begin()];
    o[1] = pts[std::min_element(d.begin(), d.end()) - d.begin()];
    o[3] = pts[std::max_element(d.begin(), d.end()) - d.begin()];
    pts = o;
}

// --- quad validation ---------------------------------------------

struct Candidate {
    std::vector<cv::Point> quad;
    double area;
    double edgeScore;   // average gradient magnitude along boundary
};

static bool isGoodQuad(const std::vector<cv::Point>& quad, double imgArea,
                       int imgW, int imgH) {
    double area = cv::contourArea(quad);
    if (area < imgArea * 0.05 || area > imgArea * 0.85) return false;
    if (!cv::isContourConvex(quad)) return false;

    // Reject quads where 3+ corners sit on the image border
    int borderMargin = 5;
    int borderCount = 0;
    for (auto& p : quad) {
        if (p.x <= borderMargin || p.y <= borderMargin ||
            p.x >= imgW - borderMargin - 1 || p.y >= imgH - borderMargin - 1)
            borderCount++;
    }
    if (borderCount >= 3) return false;

    double maxCos = 0;
    for (int j = 2; j < 5; j++) {
        double cos = std::fabs(angleCos(quad[j % 4], quad[j - 2], quad[j - 1]));
        if (cos > maxCos) maxCos = cos;
    }
    return maxCos < 0.4;
}

// Compute average gradient magnitude along the 4 edges of a quad.
// Higher = stronger real edges in the image along this quad's boundary.
static double computeEdgeScore(const std::vector<cv::Point>& quad,
                               const cv::Mat& gradMag) {
    double totalGrad = 0;
    int numSamples = 0;
    for (int i = 0; i < 4; i++) {
        cv::Point p1 = quad[i], p2 = quad[(i + 1) % 4];
        double edgeLen = cv::norm(p2 - p1);
        int nSamples = std::max(10, (int)edgeLen);
        for (int s = 0; s < nSamples; s++) {
            float t = (float)s / nSamples;
            int x = (int)(p1.x + t * (p2.x - p1.x));
            int y = (int)(p1.y + t * (p2.y - p1.y));
            if (x >= 0 && x < gradMag.cols && y >= 0 && y < gradMag.rows) {
                totalGrad += gradMag.at<float>(y, x);
                numSamples++;
            }
        }
    }
    return numSamples > 0 ? totalGrad / numSamples : 0;
}

// --- candidate collection ----------------------------------------

// Extract quad candidates from a binary/edge image into the list.
static void collectQuads(const cv::Mat& edges, double imgArea,
                         const cv::Mat& gradMag,
                         std::vector<Candidate>& candidates) {
    // Zero out borders to prevent frame-spanning contours
    cv::Mat clean = edges.clone();
    int border = 5;
    clean.rowRange(0, border).setTo(0);
    clean.rowRange(clean.rows - border, clean.rows).setTo(0);
    clean.colRange(0, border).setTo(0);
    clean.colRange(clean.cols - border, clean.cols).setTo(0);

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(clean, contours, cv::RETR_EXTERNAL,
                     cv::CHAIN_APPROX_SIMPLE);

    // Sort by area descending, check only top candidates
    std::sort(contours.begin(), contours.end(),
        [](const auto& a, const auto& b) {
            return cv::contourArea(a) > cv::contourArea(b);
        });

    int limit = std::min((int)contours.size(), 20);
    for (double eps : {0.02, 0.04}) {
        for (int i = 0; i < limit; i++) {
            double peri = cv::arcLength(contours[i], true);
            std::vector<cv::Point> approx;
            cv::approxPolyDP(contours[i], approx, eps * peri, true);
            if (approx.size() == 4 &&
                isGoodQuad(approx, imgArea, clean.cols, clean.rows)) {
                double area = cv::contourArea(approx);
                double score = computeEdgeScore(approx, gradMag);
                candidates.push_back({approx, area, score});
            }
        }
    }
}

// --- detection strategies ----------------------------------------

// Strategy 1: Per-channel Canny + binary thresholds (squares-demo)
static void findSquaresMultiChannel(const cv::Mat& img, double imgArea,
                                    const cv::Mat& gradMag,
                                    std::vector<Candidate>& candidates) {
    cv::Mat pyr, filtered;
    cv::pyrDown(img, pyr, cv::Size(img.cols / 2, img.rows / 2));
    cv::pyrUp(pyr, filtered, img.size());

    cv::Mat gray0(filtered.size(), CV_8U);
    for (int c = 0; c < filtered.channels(); c++) {
        int ch[] = {c, 0};
        cv::mixChannels(&filtered, 1, &gray0, 1, ch, 1);

        // Canny pass
        cv::Mat binary;
        cv::Canny(gray0, binary, 20, 80, 3);
        cv::dilate(binary, binary, cv::Mat(), cv::Point(-1, -1));
        collectQuads(binary, imgArea, gradMag, candidates);

        // Binary threshold passes
        for (int l = 1; l <= 6; l++) {
            binary = gray0 >= (l * 255 / 7);
            collectQuads(binary, imgArea, gradMag, candidates);
        }
    }
}

// Strategy 2: Morphological gradient
static void findByMorphGradient(const cv::Mat& img, double imgArea,
                                const cv::Mat& gradMag,
                                std::vector<Candidate>& candidates) {
    cv::Mat gray;
    if (img.channels() >= 3)
        cv::cvtColor(img, gray, cv::COLOR_BGR2GRAY);
    else
        gray = img;

    for (int kSize : {3, 5}) {
        cv::Mat blurred;
        cv::medianBlur(gray, blurred, 7);
        cv::Mat elem = cv::getStructuringElement(cv::MORPH_RECT,
                                                  cv::Size(kSize, kSize));
        cv::Mat dilated, eroded, gradient;
        cv::dilate(blurred, dilated, elem);
        cv::erode(blurred, eroded, elem);
        cv::subtract(dilated, eroded, gradient);

        cv::Mat binary;
        cv::threshold(gradient, binary, 0, 255,
                      cv::THRESH_BINARY | cv::THRESH_OTSU);
        cv::Mat closeElem = cv::getStructuringElement(cv::MORPH_RECT,
                                                       cv::Size(3, 3));
        cv::morphologyEx(binary, binary, cv::MORPH_CLOSE, closeElem,
                         cv::Point(-1,-1), 2);
        collectQuads(binary, imgArea, gradMag, candidates);
    }
}

// Strategy 3: HSV saturation (both directions)
static void findBySaturation(const cv::Mat& bgr, double imgArea,
                             const cv::Mat& gradMag,
                             std::vector<Candidate>& candidates) {
    cv::Mat hsv;
    cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);
    std::vector<cv::Mat> ch;
    cv::split(hsv, ch);

    cv::Mat sat;
    cv::GaussianBlur(ch[1], sat, cv::Size(7, 7), 0);

    cv::Mat tInv, tNorm;
    cv::threshold(sat, tInv, 0, 255,
                  cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
    cv::threshold(sat, tNorm, 0, 255,
                  cv::THRESH_BINARY | cv::THRESH_OTSU);

    cv::Mat kClose = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(9, 9));
    for (auto& t : {tInv, tNorm}) {
        cv::Mat cleaned;
        cv::morphologyEx(t, cleaned, cv::MORPH_CLOSE, kClose,
                         cv::Point(-1,-1), 3);
        cv::morphologyEx(cleaned, cleaned, cv::MORPH_OPEN,
            cv::getStructuringElement(cv::MORPH_RECT, cv::Size(5, 5)),
            cv::Point(-1,-1), 1);
        collectQuads(cleaned, imgArea, gradMag, candidates);
    }
}

// Strategy 4: Background colour distance
static void findByColorDistance(const cv::Mat& bgr, double imgArea,
                                const cv::Mat& gradMag,
                                std::vector<Candidate>& candidates) {
    int h = bgr.rows, w = bgr.cols;
    double bSum = 0, gSum = 0, rSum = 0;
    int n = 0;
    for (int x = 0; x < w; x += 2) {
        auto p0 = bgr.at<cv::Vec3b>(0, x);
        auto p1 = bgr.at<cv::Vec3b>(h - 1, x);
        bSum += p0[0] + p1[0]; gSum += p0[1] + p1[1]; rSum += p0[2] + p1[2];
        n += 2;
    }
    for (int y = 1; y < h - 1; y += 2) {
        auto p0 = bgr.at<cv::Vec3b>(y, 0);
        auto p1 = bgr.at<cv::Vec3b>(y, w - 1);
        bSum += p0[0] + p1[0]; gSum += p0[1] + p1[1]; rSum += p0[2] + p1[2];
        n += 2;
    }
    double bM = bSum / n, gM = gSum / n, rM = rSum / n;

    cv::Mat dist(h, w, CV_32FC1);
    for (int y = 0; y < h; y++) {
        const auto* row = bgr.ptr<cv::Vec3b>(y);
        auto* drow = dist.ptr<float>(y);
        for (int x = 0; x < w; x++) {
            double db = row[x][0] - bM, dg = row[x][1] - gM,
                   dr = row[x][2] - rM;
            drow[x] = (float)std::sqrt(db*db + dg*dg + dr*dr);
        }
    }
    cv::Mat distU8;
    cv::normalize(dist, distU8, 0, 255, cv::NORM_MINMAX);
    distU8.convertTo(distU8, CV_8UC1);

    cv::Mat binary;
    cv::threshold(distU8, binary, 0, 255,
                  cv::THRESH_BINARY | cv::THRESH_OTSU);
    cv::Mat kClose = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(9, 9));
    cv::morphologyEx(binary, binary, cv::MORPH_CLOSE, kClose,
                     cv::Point(-1,-1), 3);
    collectQuads(binary, imgArea, gradMag, candidates);
}

// Strategy 5: Lab L/a*/b* edges
static void findByLabEdges(const cv::Mat& bgr, double imgArea,
                           const cv::Mat& gradMag,
                           std::vector<Candidate>& candidates) {
    cv::Mat lab;
    cv::cvtColor(bgr, lab, cv::COLOR_BGR2Lab);
    std::vector<cv::Mat> ch;
    cv::split(lab, ch);

    for (int lo : {10, 25, 45}) {
        cv::Mat l, a, b;
        cv::GaussianBlur(ch[0], l, cv::Size(5, 5), 0);
        cv::GaussianBlur(ch[1], a, cv::Size(5, 5), 0);
        cv::GaussianBlur(ch[2], b, cv::Size(5, 5), 0);
        cv::Mat eL, eA, eB, combined;
        cv::Canny(l, eL, lo, lo * 3);
        cv::Canny(a, eA, lo, lo * 3);
        cv::Canny(b, eB, lo, lo * 3);
        cv::bitwise_or(eA, eB, combined);
        cv::bitwise_or(combined, eL, combined);
        cv::dilate(combined, combined,
            cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5)));
        collectQuads(combined, imgArea, gradMag, candidates);
    }
}

// Strategy 6: CLAHE-enhanced Canny
static void findByCLAHECanny(const cv::Mat& bgr, double imgArea,
                             const cv::Mat& gradMag,
                             std::vector<Candidate>& candidates) {
    cv::Mat gray;
    if (bgr.channels() >= 3)
        cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
    else
        gray = bgr;

    auto clahe = cv::createCLAHE(3.0, cv::Size(8, 8));
    cv::Mat enhanced;
    clahe->apply(gray, enhanced);

    for (int lo : {20, 40, 70}) {
        cv::Mat blurred, edges;
        cv::GaussianBlur(enhanced, blurred, cv::Size(5, 5), 0);
        cv::Canny(blurred, edges, lo, lo * 2.5);
        cv::dilate(edges, edges,
            cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5)));
        collectQuads(edges, imgArea, gradMag, candidates);
    }
}

// --- main pipeline ------------------------------------------------

static std::vector<cv::Point> detectDocument(const cv::Mat& bgr) {
    // Resize to workable resolution
    cv::Mat small;
    double scale = 1.0;
    const int TARGET = 600;
    if (std::max(bgr.rows, bgr.cols) > TARGET) {
        scale = (double)TARGET / std::max(bgr.rows, bgr.cols);
        cv::resize(bgr, small, cv::Size(), scale, scale, cv::INTER_AREA);
    } else {
        small = bgr.clone();
    }
    double imgArea = small.rows * small.cols;

    LOGD("detectDocument: input=%dx%d small=%dx%d scale=%.4f",
         bgr.cols, bgr.rows, small.cols, small.rows, scale);

    // Pre-compute gradient magnitude map (used to score ALL candidates)
    cv::Mat gray;
    cv::cvtColor(small, gray, cv::COLOR_BGR2GRAY);
    cv::Mat gradX, gradY, gradMag;
    cv::Sobel(gray, gradX, CV_32F, 1, 0);
    cv::Sobel(gray, gradY, CV_32F, 0, 1);
    cv::magnitude(gradX, gradY, gradMag);

    // Collect ALL valid quad candidates from all strategies
    std::vector<Candidate> candidates;

    findSquaresMultiChannel(small, imgArea, gradMag, candidates);
    LOGD("  after multiChannel: %d candidates", (int)candidates.size());

    findByMorphGradient(small, imgArea, gradMag, candidates);
    LOGD("  after morphGradient: %d candidates", (int)candidates.size());

    findBySaturation(small, imgArea, gradMag, candidates);
    LOGD("  after saturation: %d candidates", (int)candidates.size());

    findByColorDistance(small, imgArea, gradMag, candidates);
    LOGD("  after colorDist: %d candidates", (int)candidates.size());

    findByLabEdges(small, imgArea, gradMag, candidates);
    LOGD("  after labEdges: %d candidates", (int)candidates.size());

    findByCLAHECanny(small, imgArea, gradMag, candidates);
    LOGD("  after claheCanny: %d total candidates", (int)candidates.size());

    if (candidates.empty()) {
        LOGD("  RESULT: no candidates found");
        return {};
    }

    // Combined score = edgeScore * areaRatio
    // Linear area weight strongly favours bigger quads while still
    // letting edge quality break ties between similar-sized candidates.
    //  - Tiny text quad (7% area, edge 260):  260 * 0.07 = 18
    //  - Real document  (40% area, edge 60):   60 * 0.40 = 24  ← wins!
    //  - Big false pos  (80% area, edge 30):   30 * 0.80 = 24
    for (auto& c : candidates) {
        double areaRatio = c.area / imgArea;
        c.edgeScore = c.edgeScore * areaRatio;
    }

    auto& best = *std::max_element(candidates.begin(), candidates.end(),
        [](const Candidate& a, const Candidate& b) {
            return a.edgeScore < b.edgeScore;
        });

    LOGD("  BEST: combinedScore=%.1f area=%.0f (%.1f%%) corners=[%d,%d][%d,%d][%d,%d][%d,%d]",
         best.edgeScore, best.area, best.area / imgArea * 100,
         best.quad[0].x, best.quad[0].y, best.quad[1].x, best.quad[1].y,
         best.quad[2].x, best.quad[2].y, best.quad[3].x, best.quad[3].y);

    // Log top-5 candidates for debugging
    std::sort(candidates.begin(), candidates.end(),
        [](const Candidate& a, const Candidate& b) {
            return a.edgeScore > b.edgeScore;
        });
    int logN = std::min(5, (int)candidates.size());
    for (int i = 0; i < logN; i++) {
        auto& c = candidates[i];
        LOGD("  top%d: combined=%.1f area=%.1f%%", i+1,
             c.edgeScore, c.area / imgArea * 100);
    }

    // Scale back to original coordinates
    auto result = best.quad;
    for (auto& pt : result) {
        pt.x = (int)std::round(pt.x / scale);
        pt.y = (int)std::round(pt.y / scale);
    }
    orderPoints(result);
    return result;
}

// ========== JNI ==================================================

static jfloatArray quadToJni(JNIEnv* env,
                             const std::vector<cv::Point>& quad) {
    if (quad.empty()) return nullptr;
    jfloatArray result = env->NewFloatArray(8);
    float pts[8];
    for (int i = 0; i < 4; i++) {
        pts[i * 2]     = (float)quad[i].x;
        pts[i * 2 + 1] = (float)quad[i].y;
    }
    env->SetFloatArrayRegion(result, 0, 8, pts);
    return result;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_trudido_scanner_NativeScanner_findDocumentCorners(
        JNIEnv *env, jobject, jlong addr) {
    cv::Mat& frame = *(cv::Mat*)addr;
    cv::Mat bgr;
    if (frame.channels() == 4)
        cv::cvtColor(frame, bgr, cv::COLOR_RGBA2BGR);
    else if (frame.channels() == 3)
        bgr = frame;
    else
        cv::cvtColor(frame, bgr, cv::COLOR_GRAY2BGR);
    return quadToJni(env, detectDocument(bgr));
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_trudido_scanner_NativeScanner_findDocumentCornersColor(
        JNIEnv *env, jobject, jlong addr) {
    cv::Mat& frame = *(cv::Mat*)addr;
    cv::Mat bgr;
    if (frame.channels() == 4)
        cv::cvtColor(frame, bgr, cv::COLOR_RGBA2BGR);
    else if (frame.channels() == 3)
        bgr = frame;
    else
        cv::cvtColor(frame, bgr, cv::COLOR_GRAY2BGR);
    return quadToJni(env, detectDocument(bgr));
}
