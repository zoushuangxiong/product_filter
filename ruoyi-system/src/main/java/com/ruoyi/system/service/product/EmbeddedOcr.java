package com.ruoyi.system.service.product;

import ai.onnxruntime.*;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeDetector;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;

/** Java 进程内的 PP-OCRv4，沿用 RapidOCR 1.4.4 模型；预处理细节差异可能影响识别结果。 */
final class EmbeddedOcr
{
    private static EmbeddedOcr instance;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    // det 定位文字区域，cls 判断文字方向，rec 识别文字。
    private final OrtSession det, cls, rec;
    private final List<String> characters;

    /** 首次使用时加载 JAR 内的模型，后续任务复用，避免反复分配模型内存。 */
    static synchronized EmbeddedOcr get() throws Exception
    {
        if (instance == null) instance = new EmbeddedOcr();
        return instance;
    }

    private EmbeddedOcr() throws Exception
    {
        nu.pattern.OpenCV.loadLocally();
        Core.setNumThreads(2);
        OrtSession detector = null, classifier = null, recognizer = null;
        try (var options = new OrtSession.SessionOptions())
        {
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
            detector = session("ch_PP-OCRv4_det_infer.onnx", options);
            classifier = session("ch_ppocr_mobile_v2.0_cls_infer.onnx", options);
            recognizer = session("ch_PP-OCRv4_rec_infer.onnx", options);
            characters = new ArrayList<>(); characters.add("");
            // 直接读取 UTF-8 字典，避免 JNI 元数据转换损坏部分扩展字符。
            try (var in = EmbeddedOcr.class.getResourceAsStream("/ocr/characters.txt"))
            {
                if (in == null) throw new IOException("Missing OCR character dictionary");
                characters.addAll(new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).lines().toList());
            }
            characters.add(" ");
            long dictionarySize = ((TensorInfo)recognizer.getOutputInfo().values().iterator().next().getInfo()).getShape()[2];
            if (characters.size() != dictionarySize) throw new IOException("OCR dictionary does not match model");
        }
        catch (Exception | LinkageError e)
        {
            for (OrtSession s : new OrtSession[]{detector, classifier, recognizer}) if (s != null) s.close();
            throw e;
        }
        det = detector; cls = classifier; rec = recognizer;
    }

    private OrtSession session(String name, OrtSession.SessionOptions options) throws Exception
    {
        try (var in = EmbeddedOcr.class.getResourceAsStream("/ocr/" + name))
        {
            if (in == null) throw new IOException("Missing bundled OCR model: " + name);
            return env.createSession(in.readAllBytes(), options);
        }
    }

    /** 两个任务线程串行共享 OCR，限制同时推理占用的原生内存。 */
    synchronized ScanGateway.Inspection inspect(byte[] bytes) throws Exception
    {
        if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024) throw new IOException("Invalid image size");
        // 对 ImageIO 支持的格式先检查尺寸，避免直接解码超大图片。
        try (var in = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes)))
        {
            var readers = javax.imageio.ImageIO.getImageReaders(in);
            if (readers.hasNext())
            {
                var reader = readers.next();
                try { reader.setInput(in); if ((long)reader.getWidth(0) * reader.getHeight(0) > 40_000_000) throw new IOException("Image too large"); }
                finally { reader.dispose(); }
            }
        }
        // 按单帧彩色图解码；OpenCV 按 JPEG 的 EXIF 方向信息调整朝向。
        MatOfByte encoded = new MatOfByte(bytes);
        Mat image = new Mat(), preview = new Mat(); MatOfByte jpg = new MatOfByte();
        try
        {
            image.release(); image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
            if (image.empty() || (long) image.cols() * image.rows() > 40_000_000) throw new IOException("Unsupported or oversized image");
            ScanModels.Ocr result = new ScanModels.Ocr();
            result.width = image.cols(); result.height = image.rows(); result.engine = "PP-OCRv4 / Java ONNX";
            // 长详情图按 2000 像素切片，每次前进 1800；重叠区按文字中心位置去重。
            boolean tiled = image.rows() > Math.max(2000, image.cols() * 3);
            for (int top = 0; top < image.rows(); top += tiled ? 1800 : image.rows())
            {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int bottom = tiled ? Math.min(top + 2000, image.rows()) : image.rows();
                Mat tile = image.submat(top, bottom, 0, image.cols());
                try
                {
                    for (ScanModels.Line line : recognize(tile))
                    {
                        double center = line.box.stream().mapToDouble(p -> p.get(1)).average().orElseThrow() + top;
                        if (top > 0 && center < top + 100 || tiled && bottom < image.rows() && center >= top + 1900) continue;
                        for (List<Double> point : line.box) point.set(1, point.get(1) + top);
                        result.lines.add(line);
                    }
                }
                finally { tile.release(); }
            }
            // 只缩小展示预览，识别坐标保留在原图尺寸下，供前端按比例标框。
            double scale = Math.min(1, Math.min(1400.0 / image.cols(), 12000.0 / image.rows()));
            Imgproc.resize(image, preview, new Size(Math.max(1, Math.round(image.cols() * scale)), Math.max(1, Math.round(image.rows() * scale))), 0, 0, Imgproc.INTER_AREA);
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85);
            try { if (!Imgcodecs.imencode(".jpg", preview, jpg, params)) throw new IOException("Preview encoding failed"); }
            finally { params.release(); }
            int qrCodes = 0;
            QRCodeDetector qr = new QRCodeDetector();
            Mat points = new Mat();
            List<Mat> straight = new ArrayList<>();
            try
            {
                List<String> decoded = new ArrayList<>();
                if (qr.detectAndDecodeMulti(image, decoded, points, straight)) qrCodes = decoded.size();
            }
            catch (RuntimeException ignored) { /* 个别二维码图片不支持解码时继续完成 OCR。 */ }
            finally { points.release(); for (Mat code : straight) code.release(); }
            return new ScanGateway.Inspection(result, jpg.toArray(), qrCodes);
        }
        finally { encoded.release(); image.release(); preview.release(); jpg.release(); }
    }

    private List<ScanModels.Line> recognize(Mat original) throws Exception
    {
        Mat image = new Mat();
        List<Mat> crops = new ArrayList<>();
        try
        {
            double scale = Math.min(1, 2000.0 / Math.max(original.rows(), original.cols()));
            int h = original.rows(), w = original.cols();
            if (scale < 1) { h = (int)Math.rint((int)(h * scale) / 32.0) * 32; w = (int)Math.rint((int)(w * scale) / 32.0) * 32; }
            if (h <= 0 || w <= 0) throw new IOException("Image aspect ratio too large");
            if (Math.min(h, w) < 30) { double up = 30.0 / Math.min(h, w); h = (int)Math.round(h * up); w = (int)Math.round(w * up); }
            Imgproc.resize(original, image, new Size(w, h));
            int padding = h <= 30 || (double)w / h > 8 ? Math.abs(Math.max(w / 8, 30) * 2 - h) / 2 : 0;
            if (padding > 0) Core.copyMakeBorder(image, image, padding, padding, 0, 0, Core.BORDER_CONSTANT, Scalar.all(0));
            List<org.opencv.core.Point[]> boxes = detect(image);
            boxes.sort(Comparator.<org.opencv.core.Point[]>comparingDouble(b -> b[0].y).thenComparingDouble(b -> b[0].x));
            for (int i = 0; i < boxes.size() - 1; i++)
                for (int j = i; j >= 0; j--)
                    if (Math.abs(boxes.get(j+1)[0].y - boxes.get(j)[0].y) < 10 && boxes.get(j+1)[0].x < boxes.get(j)[0].x)
                        Collections.swap(boxes, j, j+1);
                    else break;
            for (var box : boxes) crops.add(crop(image, box));
            List<Integer> order = new ArrayList<>(); for (int i = 0; i < crops.size(); i++) order.add(i);
            order.sort(Comparator.comparingDouble(i -> (double)crops.get(i).cols() / crops.get(i).rows()));
            ScanModels.Line[] lines = new ScanModels.Line[crops.size()];
            // 按宽高比排序，每 6 张文字裁片一批并补零，与原 Python 流程保持接近。
            for (int from = 0; from < order.size(); from += 6)
            {
                int to = Math.min(from + 6, order.size());
                List<Mat> batch = new ArrayList<>();
                for (int i = from; i < to; i++) batch.add(crops.get(order.get(i)));
                float[][] angles = (float[][])infer(cls, tensor(batch, 48, 192, true), new long[]{batch.size(),3,48,192});
                for (int i = 0; i < batch.size(); i++) if (angles[i][1] > 0.9 && angles[i][1] > angles[i][0]) Core.rotate(batch.get(i), batch.get(i), Core.ROTATE_180);
                double ratio = 320.0 / 48;
                for (Mat m : batch) ratio = Math.max(ratio, (double)m.cols() / m.rows());
                int recWidth = (int)(48 * ratio);
                if (recWidth > 16384) throw new IOException("Text region too wide");
                float[][][] predictions = (float[][][])infer(rec, tensor(batch, 48, recWidth, true), new long[]{batch.size(),3,48,recWidth});
                for (int i = 0; i < batch.size(); i++)
                {
                    StringBuilder text = new StringBuilder(); double confidence = 0; int count = 0, previous = -1;
                    for (float[] step : predictions[i])
                    {
                        int best = 0; for (int k = 1; k < step.length; k++) if (step[k] > step[best]) best = k;
                        if (best > 0 && best != previous) { text.append(characters.get(best)); confidence += step[best]; count++; }
                        previous = best;
                    }
                    // 与原模型流程一致：低于 0.5 不输出；0.5～0.6 的结果由业务层标为待核查。
                    if (count == 0 || confidence / count < 0.5) continue;
                    int index = order.get(from + i); ScanModels.Line line = new ScanModels.Line();
                    line.text = text.toString(); line.score = confidence / count; line.box = new ArrayList<>();
                    for (var p : boxes.get(index)) line.box.add(new ArrayList<>(List.of(
                        clamp(p.x * original.cols() / w, 0, original.cols()), clamp((p.y - padding) * original.rows() / h, 0, original.rows()))));
                    lines[index] = line;
                }
            }
            return Arrays.stream(lines).filter(Objects::nonNull).toList();
        }
        finally { image.release(); crops.forEach(Mat::release); }
    }

    private Object infer(OrtSession session, float[] data, long[] shape) throws OrtException
    {
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
             OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), input)))
        { return result.get(0).getValue(); }
    }

    /** 将 BGR 图片转为模型要求的 NCHW 浮点数组，按 0.5 均值/标准差归一化。 */
    private float[] tensor(List<Mat> images, int height, int width, boolean keepRatio)
    {
        float[] data = new float[images.size() * 3 * height * width];
        for (int n = 0; n < images.size(); n++)
        {
            Mat image = images.get(n), resized = new Mat();
            int rw = keepRatio ? Math.min(width, (int)Math.ceil((double)height * image.cols() / image.rows())) : width;
            try
            {
                Imgproc.resize(image, resized, new Size(rw, height)); byte[] pixels = new byte[height * rw * 3]; resized.get(0,0,pixels);
                for (int y = 0; y < height; y++) for (int x = 0; x < rw; x++) for (int c = 0; c < 3; c++)
                    data[((n * 3 + c) * height + y) * width + x] = ((pixels[(y * rw + x) * 3 + c] & 255) / 255f - 0.5f) / 0.5f;
            }
            finally { resized.release(); }
        }
        return data;
    }

    private List<org.opencv.core.Point[]> detect(Mat image) throws Exception
    {
        double ratio = Math.max(1, 736.0 / Math.min(image.rows(), image.cols()));
        int h = Math.max(32, (int)Math.rint((int)(image.rows()*ratio) / 32.0)*32);
        int w = Math.max(32, (int)Math.rint((int)(image.cols()*ratio) / 32.0)*32);
        if ((long)h * w > 16_000_000) throw new IOException("Detector input too large");
        float[][] map = ((float[][][][])infer(det, tensor(List.of(image), h, w, false), new long[]{1,3,h,w}))[0][0];
        int mh = map.length, mw = map[0].length;
        Mat scores = new Mat(mh,mw,CvType.CV_32F), mask = new Mat(mh,mw,CvType.CV_8U);
        Mat kernel = Mat.ones(2,2,CvType.CV_8U), hierarchy = new Mat(); List<MatOfPoint> contours = new ArrayList<>();
        try
        {
            byte[] maskRow = new byte[mw];
            for (int y=0;y<mh;y++) { scores.put(y,0,map[y]); for (int x=0;x<mw;x++) maskRow[x]=(byte)(map[y][x]>0.3?255:0); mask.put(y,0,maskRow); }
            Imgproc.dilate(mask,mask,kernel); Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
            List<org.opencv.core.Point[]> boxes = new ArrayList<>();
            for (int i=0;i<Math.min(1000,contours.size());i++)
            {
                var box = miniBox(contours.get(i).toArray());
                if (shortSide(box)<3 || boxScore(scores,box)<0.5) continue;
                Coordinate[] coords = new Coordinate[5]; double perimeter = 0, area = 0;
                for (int j=0;j<4;j++) { coords[j]=new Coordinate((long)box[j].x,(long)box[j].y); var next=box[(j+1)%4]; perimeter+=distance(box[j],next); area+=box[j].x*next.y-next.x*box[j].y; }
                coords[4]=coords[0]; if (perimeter==0) continue;
                // DBNet 扩框：按面积/周长算外扩距离，使用圆角避免切掉字符边缘。
                var expanded = BufferOp.bufferOp(new GeometryFactory().createPolygon(coords), Math.abs(area)*0.5*1.6/perimeter, new BufferParameters(8));
                if (expanded.isEmpty()) continue;
                box = miniBox(Arrays.stream(expanded.getCoordinates()).map(c->new org.opencv.core.Point(Math.rint(c.x),Math.rint(c.y))).toArray(org.opencv.core.Point[]::new));
                if (shortSide(box)<5) continue;
                for (var p:box) { p.x=clamp(Math.rint(p.x/mw*image.cols()),0,image.cols()-1); p.y=clamp(Math.rint(p.y/mh*image.rows()),0,image.rows()-1); }
                if (distance(box[0],box[1])<=3 || distance(box[0],box[3])<=3) continue;
                boxes.add(box);
            }
            return boxes;
        }
        finally { scores.release(); mask.release(); kernel.release(); hierarchy.release(); contours.forEach(Mat::release); }
    }

    private static org.opencv.core.Point[] miniBox(org.opencv.core.Point[] points)
    {
        MatOfPoint2f contour = new MatOfPoint2f(points);
        try
        {
            org.opencv.core.Point[] p=new org.opencv.core.Point[4]; Imgproc.minAreaRect(contour).points(p);
            Arrays.sort(p,Comparator.comparingDouble(a->a.x));
            var tl=p[0].y<p[1].y?p[0]:p[1]; var bl=p[0].y<p[1].y?p[1]:p[0];
            var tr=p[2].y<p[3].y?p[2]:p[3]; var br=p[2].y<p[3].y?p[3]:p[2];
            return new org.opencv.core.Point[]{tl,tr,br,bl};
        }
        finally { contour.release(); }
    }
    private static double shortSide(org.opencv.core.Point[] p) { return Math.min(distance(p[0],p[1]),distance(p[0],p[3])); }
    private static double distance(org.opencv.core.Point a, org.opencv.core.Point b) { return Math.hypot(a.x-b.x,a.y-b.y); }
    private static double clamp(double x,double min,double max) { return Math.max(min,Math.min(max,x)); }

    private static double boxScore(Mat scores, org.opencv.core.Point[] box)
    {
        int x0=(int)clamp(Math.floor(Arrays.stream(box).mapToDouble(p->p.x).min().orElseThrow()),0,scores.cols()-1);
        int x1=(int)clamp(Math.ceil(Arrays.stream(box).mapToDouble(p->p.x).max().orElseThrow()),0,scores.cols()-1);
        int y0=(int)clamp(Math.floor(Arrays.stream(box).mapToDouble(p->p.y).min().orElseThrow()),0,scores.rows()-1);
        int y1=(int)clamp(Math.ceil(Arrays.stream(box).mapToDouble(p->p.y).max().orElseThrow()),0,scores.rows()-1);
        Mat mask=Mat.zeros(y1-y0+1,x1-x0+1,CvType.CV_8U), region=scores.submat(y0,y1+1,x0,x1+1);
        MatOfPoint polygon=new MatOfPoint(Arrays.stream(box).map(p->new org.opencv.core.Point((int)(p.x-x0),(int)(p.y-y0))).toArray(org.opencv.core.Point[]::new));
        try { Imgproc.fillPoly(mask,List.of(polygon),Scalar.all(1)); return Core.mean(region,mask).val[0]; }
        finally { mask.release(); region.release(); polygon.release(); }
    }

    /** 透视变换将倾斜文字框拉正，竖向文字裁片旋转后交给方向分类和识别模型。 */
    private static Mat crop(Mat image, org.opencv.core.Point[] box)
    {
        int w=Math.max(1,(int)Math.max(distance(box[0],box[1]),distance(box[2],box[3])));
        int h=Math.max(1,(int)Math.max(distance(box[0],box[3]),distance(box[1],box[2])));
        MatOfPoint2f src=new MatOfPoint2f(box), dst=new MatOfPoint2f(new org.opencv.core.Point(0,0),new org.opencv.core.Point(w,0),new org.opencv.core.Point(w,h),new org.opencv.core.Point(0,h));
        Mat transform=Imgproc.getPerspectiveTransform(src,dst), result=new Mat();
        try
        {
            Imgproc.warpPerspective(image,result,transform,new Size(w,h),Imgproc.INTER_CUBIC,Core.BORDER_REPLICATE);
            if ((double)h/w>=1.5) Core.rotate(result,result,Core.ROTATE_90_COUNTERCLOCKWISE);
            return result;
        }
        catch (RuntimeException e) { result.release(); throw e; }
        finally { src.release(); dst.release(); transform.release(); }
    }
}
