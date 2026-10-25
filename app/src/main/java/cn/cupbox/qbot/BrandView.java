package cn.cupbox.qbot;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A small native illustration, using the original Cupbox logo. No WebView or video decoder. */
public final class BrandView extends View {
  private final Paint paint = new Paint(3);
  private final Bitmap logo;

  public BrandView(Context context) {
    super(context);
    logo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
    setContentDescription("Cupbox 原版方盒标志");
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight(), s = Math.min(w, h);
    paint.setColor(Color.rgb(222, 233, 219));
    canvas.drawCircle(w * .51f, h * .5f, s * .43f, paint);
    paint.setColor(Color.rgb(210, 223, 246));
    canvas.drawCircle(w * .77f, h * .24f, s * .13f, paint);
    paint.setColor(Color.rgb(190, 211, 186));
    canvas.drawCircle(w * .22f, h * .73f, s * .065f, paint);
    float r = s * .31f;
    canvas.drawBitmap(logo, null, new RectF(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r), paint);
  }
}
