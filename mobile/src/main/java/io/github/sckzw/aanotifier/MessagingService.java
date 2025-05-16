package io.github.sckzw.aanotifier;


import androidx.car.app.notification.CarAppExtender;
import androidx.core.app.NotificationCompat.CarExtender;
import androidx.core.app.NotificationCompat.CarExtender.UnreadConversation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.car.app.connection.CarConnection;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessagingService extends NotificationListenerService {

    /* ---------- 定数 ---------- */
    public static final String INTENT_ACTION_SET_PREF       = "io.github.sckzw.aanotifier.INTENT_ACTION_SET_PREF";
    public static final String INTENT_ACTION_READ_MESSAGE   = "io.github.sckzw.aanotifier.INTENT_ACTION_READ_MESSAGE";
    public static final String INTENT_ACTION_REPLY_MESSAGE  = "io.github.sckzw.aanotifier.INTENT_ACTION_REPLY_MESSAGE";
    public static final String CONVERSATION_ID              = "conversation_id";
    public static final String EXTRA_VOICE_REPLY            = "extra_voice_reply";

    private static final String AANOTIFIER_PACKAGE_NAME = "io.github.sckzw.aanotifier";
    private static final String TAG = MessagingService.class.getSimpleName();

    /* ---------- 左ペインに履歴を積むためのバッファ ---------- */
    private static final int MAX_HISTORY = 5;                   // 1 枚のカードに残すメッセージ行数
    private final Map<String, Deque<NotificationCompat.MessagingStyle.Message>> historyMap =
            new ConcurrentHashMap<>();

    /* ---------- フィールド ---------- */
    private PreferenceBroadcastReceiver mPreferenceBroadcastReceiver;
    private NotificationManagerCompat   mNotificationManager;
    private LiveData<Integer>           mConnectionTypeLiveData;
    private Observer<Integer>           mConnectionTypeObserver;
    private Integer                     mConnectionType;

    private String  mAvailableAppList;
    private boolean mAndroidAutoNotification;
    private boolean mCarModeNotification;
    private boolean mCarExtenderNotification;
    private boolean mMediaSessionNotification;
    private boolean mOngoingNotification;
    private boolean mSpuriousNotification;

    /* ********************************************************************** */
    /* onCreate / onDestroy                                                   */
    /* ********************************************************************** */
    @Override
    public void onCreate() {
        Context context = getApplicationContext();

        /* 設定変更ブロードキャスト受信登録 */
        mPreferenceBroadcastReceiver = new PreferenceBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter(INTENT_ACTION_SET_PREF);
        LocalBroadcastManager.getInstance(context)
                .registerReceiver(mPreferenceBroadcastReceiver, intentFilter);

        /* 通知マネージャ */
        mNotificationManager = NotificationManagerCompat.from(context);

        /* 通知チャネル（IMPORTANCE_MIN 推奨） */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(
                    new NotificationChannel(
                            AANOTIFIER_PACKAGE_NAME,
                            getString(R.string.app_name),
                            NotificationManager.IMPORTANCE_HIGH));
        }

        /* 車載接続状態の監視 */
        mConnectionTypeObserver = newConnectionType -> mConnectionType = newConnectionType;
        mConnectionTypeLiveData = new CarConnection(context).getType();
        mConnectionTypeLiveData.observeForever(mConnectionTypeObserver);

        /* SharedPreferences 読み出し */
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        mAvailableAppList   = ";" + sp.getString ("available_app_list"        , ""    ) + ";";
        mAndroidAutoNotification  = sp.getBoolean("android_auto_notification" , true  );
        mCarModeNotification      = sp.getBoolean("car_mode_notification"     , true  );
        mCarExtenderNotification  = sp.getBoolean("car_extender_notification" , false );
        mMediaSessionNotification = sp.getBoolean("media_session_notification", false );
        mOngoingNotification      = sp.getBoolean("ongoing_notification"      , false );
        mSpuriousNotification     = sp.getBoolean("spurious_notification"     , false );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPreferenceBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(getApplicationContext())
                    .unregisterReceiver(mPreferenceBroadcastReceiver);
        }
        mConnectionTypeLiveData.removeObserver(mConnectionTypeObserver);
    }

    /* ********************************************************************** */
    /* NotificationListener                                                   */
    /* ********************************************************************** */
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);

        String packageName = sbn.getPackageName();
        Log.d(TAG, "onNotificationPosted: " + packageName);

        /* 自アプリ通知は無視 */
        if (packageName.equals(AANOTIFIER_PACKAGE_NAME)) return;

        /* 各種フィルタ */
        if (!mAndroidAutoNotification)                                                       return;
        if (mCarModeNotification && mConnectionType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED) return;
        if (!mOngoingNotification && sbn.isOngoing())                                        return;
        if (!mAvailableAppList.contains(";" + packageName + ";"))                            return;

        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)                                return;
        if (n.extras != null) {
            if (!mCarExtenderNotification  && n.extras.containsKey("android.car.EXTENSIONS"))    return;
            if (!mMediaSessionNotification && n.extras.containsKey("android.mediaSession"))      return;
        }

        sendNotification(sbn);
    }

    /* ********************************************************************** */
    /* 通知を Android Auto 用に再生成                                         */
    /* ********************************************************************** */
    private void sendNotification(StatusBarNotification sbn) {




        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Context appContext = getApplicationContext();
        Notification origin = sbn.getNotification();
        Bundle extras = origin.extras;
        long timeStamp = sbn.getPostTime();
        CharSequence cs;

        /* タイトル・テキスト抽出 */
        String title = "";
        if      (extras.containsKey(Notification.EXTRA_TITLE     ) && (cs = extras.getCharSequence(Notification.EXTRA_TITLE     )) != null) title = cs.toString();
        else if (extras.containsKey(Notification.EXTRA_TITLE_BIG ) && (cs = extras.getCharSequence(Notification.EXTRA_TITLE_BIG )) != null) title = cs.toString();

        String text = "";
        if      (extras.containsKey(Notification.EXTRA_TEXT      ) && (cs = extras.getCharSequence(Notification.EXTRA_TEXT      )) != null) text = cs.toString();
        else if (extras.containsKey(Notification.EXTRA_BIG_TEXT  ) && (cs = extras.getCharSequence(Notification.EXTRA_BIG_TEXT  )) != null) text = cs.toString();
        else if (origin.tickerText != null) text = origin.tickerText.toString();

        if (!text.startsWith(title)) text = title + ": " + text;

        /* アプリ名・アイコン→ Person */
        String appName = getApplicationName(sbn.getPackageName());
        Person.Builder pb = new Person.Builder().setName(appName).setKey(sbn.getKey());
        Icon icon = origin.getLargeIcon() != null ? origin.getLargeIcon() : origin.getSmallIcon();
        if (icon != null) pb.setIcon(IconCompat.createFromIcon(appContext, icon));
        Person appPerson = pb.build();

        /* ────────── “Discord 風スタック” 用履歴構築 ────────── */
        String convKey = sbn.getPackageName() + ":" + title;               // 会話キー
        Deque<NotificationCompat.MessagingStyle.Message> q =
                historyMap.computeIfAbsent(convKey, k -> new ArrayDeque<>());
        q.addLast(new NotificationCompat.MessagingStyle.Message(text, timeStamp, appPerson));
        while (q.size() > MAX_HISTORY) q.removeFirst();

        NotificationCompat.MessagingStyle msgStyle =
                new NotificationCompat.MessagingStyle(appPerson)
                        .setConversationTitle(title)
                        .setGroupConversation(true);
        q.forEach(msgStyle::addMessage);

        /* Read / Reply Action（Invisible） */
        PendingIntent readPi = PendingIntent.getBroadcast(
                appContext, convKey.hashCode(),
                new Intent(appContext, MessageReadReceiver.class)
                        .setAction(INTENT_ACTION_READ_MESSAGE)
                        .putExtra(CONVERSATION_ID, convKey.hashCode())
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent replyPi = PendingIntent.getBroadcast(
                appContext,
                convKey.hashCode(),                           // ←キーを convKey.hashCode() に合わせても可
                new Intent(appContext, MessageReplyReceiver.class)
                        .setAction(INTENT_ACTION_REPLY_MESSAGE)
                        .putExtra(CONVERSATION_ID, convKey.hashCode())
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE   // ★ 変更点
        );

        RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_VOICE_REPLY)
                .setLabel("返信")                     // ラベル必須
                .setAllowFreeFormInput(true)          // デフォルト true
                .build();
        NotificationCompat.Action readAction  = new NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, getString(R.string.action_read_title ), readPi ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build();
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(R.drawable.ic_launcher_foreground, getString(R.string.action_reply_title), replyPi).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY    ).setShowsUserInterface(false).addRemoteInput(remoteInput).build();

        /* 通知ビルド */
        NotificationCompat.Builder nb = new NotificationCompat.Builder(appContext, AANOTIFIER_PACKAGE_NAME)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setCategory(Notification.CATEGORY_MESSAGE)          // ← 左ペイン表示の必須キー
                .setStyle(msgStyle)
                .setOnlyAlertOnce(true)                              // 更新時は鳴動抑制
                .addAction(replyAction )      // ★ 返信は“見える”アクション
                .addInvisibleAction( readAction );

        if (mOngoingNotification) nb.setOngoing(true);
        /* ─────── Car 用 UnreadConversation ─────── */
        UnreadConversation.Builder ucBuilder =
                new UnreadConversation.Builder(title)               // 会話タイトル
                        .addMessage(text)                           // 最新メッセージ
                        .setLatestTimestamp(timeStamp)
                        .setReadPendingIntent(readPi)               // 既読 PI
                        .setReplyAction(replyPi, remoteInput);      // 返信 PI & RemoteInput

        nb.extend(
                new NotificationCompat.CarExtender()
                        .setUnreadConversation(ucBuilder.build())
        );
        /* ──────────────────────────────────────── */

        /* 効果音（車載スピーカー） */
        playNotificationSound();

        /* 通知発行：tag=convKey, id=1 で上書き更新 */
        mNotificationManager.notify(convKey, 1, nb.build());

        /* 自動消去（常駐モードでなければ 1 秒後に消す） */
        if (!mSpuriousNotification && !mOngoingNotification) {
            new Handler().postDelayed(() -> mNotificationManager.cancel(convKey, 1), 1000);
        }
    }

    /* ********************************************************************** */
    /* 効果音を Media ストリームで鳴らし、終了後にフォーカス返却                */
    /* ********************************************************************** */
    private void playNotificationSound() {
        Context ctx = getApplicationContext();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)           // ← 車載器へルーティングされる
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFocusRequest focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(f -> {})
                .build();

        if (am.requestAudioFocus(focusReq) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;

        MediaPlayer mp = MediaPlayer.create(ctx, R.raw.notification_ping);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        } else {
            mp.setAudioAttributes(attrs);
        }
        mp.setOnCompletionListener(p -> {
            p.release();
            am.abandonAudioFocusRequest(focusReq);
        });
        mp.start();
    }

    /* ********************************************************************** */
    /* ユーティリティ                                                          */
    /* ********************************************************************** */
    private String getApplicationName(String packageName) {
        PackageManager pm = getApplicationContext().getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /* ********************************************************************** */
    /* Preference 変更受信                                                    */
    /* ********************************************************************** */
    private class PreferenceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = intent.getStringExtra("key");
            if (key == null) return;

            switch ( key ) {
                case "available_app_list":
                    mAvailableAppList = ";" + intent.getStringExtra( "value" ) + ";";
                    break;
                case "android_auto_notification":
                    mAndroidAutoNotification = intent.getBooleanExtra( "value", true );
                    break;
                case "car_mode_notification":
                    mCarModeNotification = intent.getBooleanExtra( "value", true );
                    break;
                case "car_extender_notification":
                    mCarExtenderNotification = intent.getBooleanExtra( "value", false );
                    break;
                case "media_session_notification":
                    mMediaSessionNotification = intent.getBooleanExtra( "value", false );
                    break;
                case "ongoing_notification":
                    mOngoingNotification = intent.getBooleanExtra( "value", false );
                    break;
                case "spurious_notification":
                    mSpuriousNotification = intent.getBooleanExtra( "value", false );
                    break;
            }
        }
    }
}
