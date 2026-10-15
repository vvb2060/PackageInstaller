package io.github.vvb2060.packageinstaller.apksig;

import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Map;

public class Span extends ClickableSpan {
    private final TextView textView;
    private final CharSequence infoText;
    private CharSequence originalText;
    private boolean isClicked = false;

    private Span(TextView textView, SpannableStringBuilder text) {
        this.textView = textView;
        text.setSpan(this, 0, text.length(),
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        infoText = text;
    }

    @Override
    public void updateDrawState(@NonNull TextPaint ds) {
    }

    @Override
    public void onClick(@NonNull View widget) {
        if (originalText == null) {
            originalText = textView.getText();
        }
        if (isClicked) {
            textView.setText(originalText, TextView.BufferType.SPANNABLE);
        } else {
            textView.setText(infoText, TextView.BufferType.SPANNABLE);
        }
        isClicked = !isClicked;
    }

    public static void appendSignInfo(SpannableStringBuilder sb, TextView textView,
                                      Map<Integer, Object> map) {
        var name = new StringBuilder();
        var full = new SpannableStringBuilder();
        var simple = new StringBuilder();
        map.forEach((blockId, data) -> {
            var isFull = false;
            var index = full.length();
            switch (blockId) {
                case Parser.APK_SIGNATURE_SCHEME_V2_BLOCK_ID -> {
                    name.append("V2 ");
                    full.append("Apk Signature v2");
                    isFull = true;
                }
                case Parser.APK_SIGNATURE_SCHEME_V3_BLOCK_ID -> {
                    name.append("V3 ");
                    full.append("Apk Signature v3");
                    isFull = true;
                }
                case Parser.APK_SIGNATURE_SCHEME_V31_BLOCK_ID -> {
                    name.append("V3.1 ");
                    full.append("Apk Signature v3.1");
                    isFull = true;
                }
                case Parser.APK_SIGNATURE_SCHEME_V32_BLOCK_ID -> {
                    name.append("V3.2 ");
                    full.append("Apk Signature v3.2");
                    isFull = true;
                }
                case Parser.V2_SOURCE_STAMP_BLOCK_ID -> {
                    name.append("StampV2 ");
                    full.append("Source Stamp v2");
                    isFull = true;
                }
                case Parser.V1_SOURCE_STAMP_BLOCK_ID -> name.append("StampV1 ");
                case Parser.GOOGLE_PLAY_PAIRIP_BLOCK_ID -> name.append("Pairip ");
                case Parser.GOOGLE_PLAY_FROSTING_BLOCK_ID -> name.append("Frosting ");
                case Parser.AGP_DEPENDENCIES_INFO_BLOCK_ID -> name.append("Deps ");
                case Parser.TENCENT_VASDOLLY_BLOCK_ID -> name.append("VasDolly ");
                case Parser.MEITUAN_WALLE_BLOCK_ID -> name.append("Walle ");
                default -> name.append("0x").append(Integer.toHexString(blockId)).append(" ");
            }
            if (isFull) {
                full.append("\n");
                full.setSpan(new RelativeSizeSpan(1.2f), index, full.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            if (data instanceof Block block) {
                for (int i = 0, size = block.signers.size(); i < size; i++) {
                    var signer = block.signers.get(i);
                    signer.appendSignerInfo(full, i + 1);
                }
            } else if (data instanceof Signer signer) {
                signer.appendSignerInfo(full, 0);
            } else if (data instanceof String string) {
                if (isFull) {
                    full.append(string).append("\n");
                } else {
                    simple.append(string).append("\n");
                }
            }
            if (isFull) full.append("\n");
        });
        if (simple.length() > 0) {
            full.append(simple);
        } else if (full.length() > 0) {
            full.delete(full.length() - 1, full.length());
        }

        sb.append(name, new Span(textView, full),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
