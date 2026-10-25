package cn.cupbox.qbot;

/** Offline facts: no network request, identifier or model call is needed while waiting. */
final class WaitFacts {
  private static final String[] FACTS = {
    "章鱼有三颗心脏，其中两颗负责把血液送往鳃。",
    "金星自转一圈约需 243 个地球日，比它绕太阳一圈还久。",
    "国际空间站大约每 90 分钟绕地球一圈。",
    "月球总以几乎同一面朝向地球，但它也在自转。",
    "声音需要介质传播，真空中听不到普通声波。",
    "彩虹其实可以是一个完整的圆，从高处有机会看到。",
    "二维码的三个大方块帮助扫码器判断位置和方向。",
    "蓝牙这个名字来自一位绰号为“蓝牙”的丹麦国王。",
    "PNG 支持透明像素，JPEG 通常更适合保存照片。",
    "Android 的版本号不等于手机厂商系统的版本号。",
    "应用切到后台后仍可能运行，具体取决于系统的后台管理。",
    "本机地址 127.0.0.1 指向当前设备自己。",
    "SHA-256 校验可以检查下载内容是否与预期文件一致。",
    "鲸鱼属于哺乳动物，需要浮到水面呼吸空气。",
    "企鹅有羽毛；它们是鸟类，游泳时用翅膀推进。",
    "水在结冰时会膨胀，因此冰的密度通常比液态水小。"
  };

  static String at(long elapsed) {
    return FACTS[(int) ((elapsed / 9000) % FACTS.length)];
  }
}
