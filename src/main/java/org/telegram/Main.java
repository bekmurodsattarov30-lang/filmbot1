package org.telegram;

import org.json.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Main extends TelegramLongPollingBot {

    private static final long   SUPER_ADMIN_ID       = 7799807980L;
    private static final String SUPER_ADMIN_USERNAME = "noyabirl1k";
    private static final String BOT_TOKEN            = "8456287018:AAHWtv88IGgH-UL9K3NZ3UZRIJriAm9aHu4";
    private static final String BOT_USERNAME         = "film1fy_bot";

    private static final int MAX_MSG_LEN = 3800;

    private static final List<Long> BROADCAST_CHANNEL_IDS = List.of(
            -1003705247131L,
            -1003837469684L
    );

    // ═══ RECORD TURLARI ═══════════════════════════════════
    record Channel(String name, long id, String link, boolean hasJoinRequest) {}

    // fileIds o'rniga: har bir qism uchun {chatId, messageId} saqlanadi
    // Format: "chatId:messageId" — CopyMessage uchun
    record Movie(String code, String title, List<String> msgRefs) {}

    private final List<Channel> channels = new CopyOnWriteArrayList<>(List.of(
            new Channel("KANAL 1", -1003705247131L, "https://t.me/+697sc9ktBMQ3YmVi", true),
            new Channel("KANAL 2", -1003837469684L, "https://t.me/+jCnxEHBOLogyMDMy", false),
            new Channel("KANAL 3", -1003896851782L, "https://t.me/+3rcCxojAHT8xYjI6", true)
    ));

    private static final String DATA_DIR = System.getenv("RAILWAY_ENVIRONMENT") != null
            ? "/data/kinobot/"
            : System.getProperty("user.home") + "/kinobot_data/";

    private JSONObject moviesJson   = new JSONObject();
    private JSONObject usersJson    = new JSONObject();
    private JSONObject adminsJson   = new JSONObject();
    private JSONObject channelsJson = new JSONObject();

    private final Set<Long> dirtyUsers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ═══ DB ═══════════════════════════════════════════════
    private void initDb() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            moviesJson   = loadJson("movies.json");
            usersJson    = loadJson("users.json");
            adminsJson   = loadJson("admins.json");
            channelsJson = loadJson("channels.json");
            loadChannelsFromDb();
            scheduler.scheduleAtFixedRate(this::flushUsers, 30, 30, TimeUnit.SECONDS);
            System.out.println("✅ DB tayyor: " + DATA_DIR);
        } catch (IOException e) { System.err.println("❌ initDb: " + e.getMessage()); }
    }

    private void flushUsers() {
        if (!dirtyUsers.isEmpty()) { saveJson("users.json", usersJson); dirtyUsers.clear(); }
    }

    private JSONObject loadJson(String f) {
        try {
            Path p = Paths.get(DATA_DIR + f);
            if (Files.exists(p)) return new JSONObject(Files.readString(p));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private synchronized void saveJson(String f, JSONObject d) {
        try {
            Files.writeString(Paths.get(DATA_DIR + f), d.toString(2),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) { System.err.println("saveJson: " + e.getMessage()); }
    }

    // ─── KANALLAR ───────────────────────────────────────
    private void loadChannelsFromDb() {
        if (channelsJson.isEmpty()) {
            for (Channel ch : channels)
                channelsJson.put(String.valueOf(ch.id()), new JSONObject()
                        .put("name", ch.name()).put("link", ch.link())
                        .put("hasJoinRequest", ch.hasJoinRequest()));
            saveJson("channels.json", channelsJson);
        } else {
            channels.clear();
            for (String cid : channelsJson.keySet()) {
                JSONObject o = channelsJson.getJSONObject(cid);
                channels.add(new Channel(o.getString("name"), Long.parseLong(cid),
                        o.getString("link"), o.optBoolean("hasJoinRequest", false)));
            }
        }
    }

    private void dbAddChannel(String name, long cid, String link, boolean hjr) {
        channelsJson.put(String.valueOf(cid), new JSONObject()
                .put("name", name).put("link", link).put("hasJoinRequest", hjr));
        saveJson("channels.json", channelsJson);
        channels.removeIf(c -> c.id() == cid);
        channels.add(new Channel(name, cid, link, hjr));
    }

    private boolean dbDelChannel(long cid) {
        if (!channelsJson.has(String.valueOf(cid))) return false;
        channelsJson.remove(String.valueOf(cid));
        saveJson("channels.json", channelsJson);
        channels.removeIf(c -> c.id() == cid);
        return true;
    }

    // ─── ADMINLAR ───────────────────────────────────────
    private boolean isAdminInDb(long uid) { return adminsJson.has(String.valueOf(uid)); }

    private void dbAddAdmin(long id, String uname, long by) {
        adminsJson.put(String.valueOf(id), new JSONObject()
                .put("username", uname != null ? uname : "").put("addedBy", by));
        saveJson("admins.json", adminsJson);
    }

    private boolean dbDelAdmin(long id) {
        if (!adminsJson.has(String.valueOf(id))) return false;
        adminsJson.remove(String.valueOf(id));
        saveJson("admins.json", adminsJson);
        return true;
    }

    private List<Long> dbListAdmins() {
        List<Long> list = new ArrayList<>();
        for (String k : adminsJson.keySet()) list.add(Long.parseLong(k));
        return list;
    }

    // ─── FILMLAR ────────────────────────────────────────
    // msgRef formati: "fromChatId:messageId"
    private void dbSaveMovie(String code, String title, List<String> msgRefs) {
        String t = (title != null && !title.isBlank()) ? title : code;
        moviesJson.put(code, new JSONObject()
                .put("title", t)
                .put("msgRefs", new JSONArray(msgRefs)));
        saveJson("movies.json", moviesJson);
        movies.put(code, new Movie(code, t, new ArrayList<>(msgRefs)));
        System.out.println("✅ " + code + " | " + t + " | " + msgRefs.size() + "q");
    }

    private boolean dbDelMovie(String code) {
        if (!moviesJson.has(code)) return false;
        moviesJson.remove(code); saveJson("movies.json", moviesJson); movies.remove(code);
        return true;
    }

    private boolean dbUpdateTitle(String code, String title) {
        if (!moviesJson.has(code)) return false;
        moviesJson.getJSONObject(code).put("title", title);
        saveJson("movies.json", moviesJson);
        Movie mv = movies.get(code);
        if (mv != null) movies.put(code, new Movie(mv.code(), title, mv.msgRefs()));
        return true;
    }

    private boolean dbUpdateCode(String oldCode, String newCode) {
        if (!moviesJson.has(oldCode)) return false;
        JSONObject d = moviesJson.getJSONObject(oldCode);
        moviesJson.remove(oldCode); moviesJson.put(newCode, d);
        saveJson("movies.json", moviesJson);
        Movie mv = movies.remove(oldCode);
        if (mv != null) movies.put(newCode, new Movie(newCode, mv.title(), mv.msgRefs()));
        return true;
    }

    private void loadMoviesFromDb() {
        movies.clear();
        for (String code : moviesJson.keySet()) {
            JSONObject o = moviesJson.getJSONObject(code);
            List<String> refs = new ArrayList<>();

            // Eski format (fileIds) bilan yangi format (msgRefs) ni qo'llab-quvvatlash
            if (o.has("msgRefs")) {
                JSONArray arr = o.getJSONArray("msgRefs");
                for (int i = 0; i < arr.length(); i++) refs.add(arr.getString(i));
            } else if (o.has("fileIds")) {
                // Eski ma'lumotlar — fileId sifatida saqlangan, "file:fileId" formatiga o'giramiz
                JSONArray arr = o.getJSONArray("fileIds");
                for (int i = 0; i < arr.length(); i++) refs.add("file:" + arr.getString(i));
            }

            movies.put(code, new Movie(code, o.getString("title"), refs));
        }
        System.out.println("✅ " + movies.size() + " ta film yuklandi.");
    }

    private List<Movie> searchMoviesAdmin(String kw) {
        String q = kw.toLowerCase();
        return movies.values().stream()
                .filter(m -> m.code().toLowerCase().contains(q) || m.title().toLowerCase().contains(q))
                .limit(10).collect(Collectors.toList());
    }

    // ─── FOYDALANUVCHILAR ───────────────────────────────
    private void dbSaveUser(long id, String uname, String fname) {
        String key = String.valueOf(id);
        if (!usersJson.has(key)) {
            usersJson.put(key, new JSONObject()
                    .put("username", uname != null ? uname : "")
                    .put("firstName", fname != null ? fname : ""));
            dirtyUsers.add(id);
        }
    }

    private int countUsers() { return usersJson.length(); }

    private List<Long> getAllUserIds() {
        List<Long> ids = new ArrayList<>();
        for (String k : usersJson.keySet()) ids.add(Long.parseLong(k));
        return ids;
    }

    // ─── ADMIN TEKSHIRUVI ───────────────────────────────
    private boolean isSuperAdmin(long uid) { return uid == SUPER_ADMIN_ID; }
    private boolean isAdmin(long uid, String uname) {
        if (uid == SUPER_ADMIN_ID) return true;
        if (uname != null && uname.equalsIgnoreCase(SUPER_ADMIN_USERNAME)) return true;
        return isAdminInDb(uid);
    }

    // ─── HOLAT ──────────────────────────────────────────
    enum State {
        NONE,
        WAITING_CODE, WAITING_VIDEOS,
        WAITING_BROADCAST,
        WAITING_DEL_CODE,
        WAITING_SEARCH,
        WAITING_CH_NAME, WAITING_CH_ID, WAITING_CH_TYPE, WAITING_CH_LINK,
        WAITING_ADD_ADMIN,
        WAITING_EDIT_CODE, WAITING_EDIT_FIELD, WAITING_EDIT_VALUE, WAITING_EDIT_VIDEOS
    }

    private final Map<String, Movie>      movies      = new ConcurrentHashMap<>();
    private final Map<Long, State>        states      = new ConcurrentHashMap<>();
    private final Map<Long, String>       pendingCode = new ConcurrentHashMap<>();
    private final Map<Long, String>       pendingTitle= new ConcurrentHashMap<>();
    private final Map<Long, List<String>> pendingVids = new ConcurrentHashMap<>();
    private final Map<Long, String>       pendingData = new ConcurrentHashMap<>();

    @Override public String getBotUsername() { return BOT_USERNAME; }
    @Override public String getBotToken()    { return BOT_TOKEN; }

    // ═══ ASOSIY UPDATE HANDLER ════════════════════════════
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasChatJoinRequest()) {
                ChatJoinRequest req = update.getChatJoinRequest();
                ApproveChatJoinRequest a = new ApproveChatJoinRequest();
                a.setChatId(String.valueOf(req.getChat().getId()));
                a.setUserId(req.getUser().getId());
                try { execute(a); } catch (TelegramApiException ignored) {}
                return;
            }

            if (update.hasCallbackQuery()) { handleCallback(update.getCallbackQuery()); return; }
            if (!update.hasMessage()) return;

            Message msg  = update.getMessage();
            long chatId  = msg.getChatId();
            long userId  = msg.getFrom().getId();
            String uname = msg.getFrom().getUserName();
            String fname = msg.getFrom().getFirstName();

            dbSaveUser(userId, uname, fname);

            // Admin handler
            if (isAdmin(userId, uname)) { handleAdmin(msg); return; }

            // /start
            if (msg.hasText() && msg.getText().startsWith("/start")) {
                if (!subscribedAll(userId)) sendSubMsg(chatId, userId);
                else sendWelcomeAnimation(chatId, fname);
                return;
            }

            if (!subscribedAll(userId)) { sendSubMsg(chatId, userId); return; }

            // Foydalanuvchi matn yuborganda — EXACT match bilan film qidiradi
            if (msg.hasText()) {
                String txt = msg.getText().trim();
                if (!txt.startsWith("/")) sendFilm(chatId, txt);
            }

        } catch (Exception e) {
            System.err.println("❌ onUpdate: " + e.getMessage());
        }
    }

    // ═══ A'ZOLIK TEKSHIRUVI ═══════════════════════════════
    private boolean subscribedAll(long uid) {
        if (isAdmin(uid, null)) return true;
        for (Channel ch : channels) {
            if (!subscribed(uid, ch.id())) return false;
        }
        return true;
    }

    private boolean subscribed(long uid, long chId) {
        try {
            GetChatMember r = new GetChatMember();
            r.setChatId(String.valueOf(chId)); r.setUserId(uid);
            String s = execute(r).getStatus();
            return s.equals("member") || s.equals("administrator")
                    || s.equals("creator") || s.equals("restricted");
        } catch (TelegramApiException e) {
            System.err.println("subscribed xato ch=" + chId + ": " + e.getMessage());
            return false;
        }
    }

    // ═══ OBUNA XABARI ═════════════════════════════════════
    private void sendSubMsg(long chatId, long uid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Channel ch : channels) {
            boolean ok = subscribed(uid, ch.id());
            InlineKeyboardButton b = new InlineKeyboardButton();
            if (ok) {
                b.setText("✅ " + ch.name());
                b.setCallbackData("noop");
            } else {
                b.setText((ch.hasJoinRequest() ? "🔐" : "📢") + " " + ch.name()
                        + (ch.hasJoinRequest() ? " (So'rov)" : "") + " — Ulaning");
                b.setUrl(ch.link());
            }
            rows.add(List.of(b));
        }
        InlineKeyboardButton chk = new InlineKeyboardButton();
        chk.setText("🔄  A'zolikni tekshirish");
        chk.setCallbackData("chk");
        rows.add(List.of(chk));

        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(
                "🎬 <b>FILMIFY</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "⛔ <b>Botdan foydalanish uchun\n" +
                        "quyidagi kanallarga ulaning!</b>\n\n" +
                        "Ulangach ✅ bo'ladi.\n" +
                        "Keyin <b>🔄 Tekshirish</b> ni bosing.\n\n" +
                        "💡 <i>🔐 — so'rov yuboring,\n" +
                        "bot avtomatik qabul qiladi.</i>"
        );
        m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows));
        execMsg(m);
    }

    // ═══ XUSH KELIBSIZ ANIMATSIYA ═════════════════════════
    private void sendWelcomeAnimation(long chatId, String fname) {
        String[] frames = {"🎬", "🎬 ·", "🎬 · ·", "🎬 · · ·", "🍿 Yuklanmoqda...", "✨ FILMIFY ✨"};
        for (String f : frames) { sendRaw(chatId, f); sleep(380); }

        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(
                "🎬 <b>FILMIFY BOT</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "👋 Salom, <b>" + escHtml(fname) + "</b>!\n\n" +
                        "🍿 <b>Qanday ishlaydi?</b>\n\n" +
                        "  1️⃣  Kanaldan film <b>kodini</b> oling\n" +
                        "  2️⃣  Kodni botga <b>yuboring</b>\n" +
                        "  3️⃣  Film <b>darhol</b> keladi!\n\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                        "🔍 Film kodini kiriting:\n" +
                        "<code>Masalan: 0001</code>"
        );
        m.setParseMode("HTML");
        m.setReplyMarkup(userKb());
        execMsg(m);
    }

    // ═══ FILM YUBORISH — CopyMessage bilan (eng ishonchli usul) ════
    private void sendFilm(long chatId, String input) {
        // EXACT match — faqat to'liq mos kod
        String code = input.trim().toUpperCase();
        Movie mv = movies.get(code);

        if (mv == null) {
            // Raqamli kod bo'lsa, 0 bilan to'ldirish (masalan: "1" -> "0001")
            if (code.matches("\\d+")) {
                String padded = String.format("%04d", Integer.parseInt(code));
                mv = movies.get(padded);
                if (mv != null) code = padded;
            }
        }

        if (mv == null) {
            SendMessage m = new SendMessage();
            m.setChatId(String.valueOf(chatId));
            m.setText(
                    "❌ <b>Film topilmadi!</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "🔍 Kod: <code>" + escHtml(code) + "</code>\n\n" +
                            "📌 <b>Nima qilish kerak?</b>\n" +
                            "• Kodni qayta tekshiring\n" +
                            "• Kanaldan to'g'ri nusxa oling\n\n" +
                            "💡 <i>Misol: <code>0001</code></i>"
            );
            m.setParseMode("HTML");
            execMsg(m);
            return;
        }

        int total = mv.msgRefs().size();

        if (total > 1) {
            SendMessage info = new SendMessage();
            info.setChatId(String.valueOf(chatId));
            info.setText(
                    "🎬 <b>" + escHtml(mv.title()) + "</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "📽 Jami <b>" + total + "</b> qism\n" +
                            "⏳ <i>Yuklanmoqda...</i>"
            );
            info.setParseMode("HTML");
            execMsg(info);
        }

        for (int i = 0; i < mv.msgRefs().size(); i++) {
            String ref = mv.msgRefs().get(i);
            boolean sent = false;

            // Yangi format: "fromChatId:messageId" — CopyMessage
            if (ref.contains(":") && !ref.startsWith("file:")) {
                try {
                    String[] parts = ref.split(":", 2);
                    String fromChatId = parts[0];
                    int messageId     = Integer.parseInt(parts[1]);

                    CopyMessage cp = new CopyMessage();
                    cp.setChatId(String.valueOf(chatId));
                    cp.setFromChatId(fromChatId);
                    cp.setMessageId(messageId);

                    // Caption qo'shish
                    String cap = total == 1
                            ? "🎬 <b>" + escHtml(mv.title()) + "</b>\n\n🤖 @" + BOT_USERNAME
                            : "🎬 <b>" + escHtml(mv.title()) + "</b>\n📌 <b>" + (i + 1) + "-qism</b> / " + total + "\n\n🤖 @" + BOT_USERNAME;
                    cp.setCaption(cap);
                    cp.setParseMode("HTML");

                    execute(cp);
                    sent = true;
                } catch (Exception e) {
                    System.err.println("CopyMessage xato: " + e.getMessage());
                }
            }

            // Eski format: "file:fileId" yoki to'g'ridan-to'g'ri fileId
            if (!sent) {
                try {
                    String fileId = ref.startsWith("file:") ? ref.substring(5) : ref;
                    org.telegram.telegrambots.meta.api.methods.send.SendVideo v =
                            new org.telegram.telegrambots.meta.api.methods.send.SendVideo();
                    v.setChatId(String.valueOf(chatId));
                    v.setVideo(new InputFile(fileId));
                    String cap = total == 1
                            ? "🎬 <b>" + escHtml(mv.title()) + "</b>\n\n🤖 @" + BOT_USERNAME
                            : "🎬 <b>" + escHtml(mv.title()) + "</b>\n📌 <b>" + (i + 1) + "-qism</b> / " + total + "\n\n🤖 @" + BOT_USERNAME;
                    v.setCaption(cap);
                    v.setParseMode("HTML");
                    execute(v);
                    sent = true;
                } catch (Exception e) {
                    System.err.println("SendVideo xato: " + e.getMessage());
                }
            }

            if (!sent) {
                sendText(chatId, "⚠️ " + (i + 1) + "-qismni yuborishda xato. Iltimos, keyinroq urinib ko'ring.");
            }

            sleep(300);
        }
    }

    // ═══ CALLBACK HANDLER ═════════════════════════════════
    private void handleCallback(CallbackQuery cb) {
        long chatId  = cb.getMessage().getChatId();
        long uid     = cb.getFrom().getId();
        String uname = cb.getFrom().getUserName();
        String fname = cb.getFrom().getFirstName();
        String data  = cb.getData();

        try { AnswerCallbackQuery a = new AnswerCallbackQuery(); a.setCallbackQueryId(cb.getId()); execute(a); }
        catch (Exception ignored) {}

        if ("noop".equals(data)) return;

        if ("chk".equals(data)) {
            if (subscribedAll(uid)) sendWelcomeAnimation(chatId, fname != null ? fname : "Do'stim");
            else sendSubMsg(chatId, uid);
            return;
        }

        if (!isAdmin(uid, uname)) return;

        if (data.startsWith("del_ch:")) {
            long cid = Long.parseLong(data.split(":")[1]);
            sendText(chatId, dbDelChannel(cid) ? "✅ Kanal o'chirildi!" : "❌ Topilmadi.");

        } else if (data.startsWith("del_admin:")) {
            long aid2 = Long.parseLong(data.split(":")[1]);
            if (aid2 == SUPER_ADMIN_ID) { sendText(chatId, "⛔ Super adminni o'chirib bo'lmaydi!"); return; }
            if (!isSuperAdmin(uid))     { sendText(chatId, "⛔ Faqat Super Admin!"); return; }
            sendText(chatId, dbDelAdmin(aid2) ? "✅ Admin o'chirildi!" : "❌ Topilmadi.");

        } else if (data.equals("ch_type:request") || data.equals("ch_type:public")) {
            boolean isReq = data.equals("ch_type:request");
            String raw = pendingData.getOrDefault(uid, "||");
            String[] parts = raw.split("\\|\\|", 2);
            String name   = parts.length > 0 ? parts[0] : "Kanal";
            String cidStr = parts.length > 1 ? parts[1] : "0";
            pendingData.put(uid, name + "||" + cidStr + "||" + (isReq ? "request" : "public"));
            states.put(uid, State.WAITING_CH_LINK);
            sendText(chatId,
                    "✅ Tur: " + (isReq ? "🔐 So'rovli" : "📢 Ochiq") + "\n\n" +
                            "4️⃣ Invite link yuboring:\n<code>https://t.me/+xxxxx</code>\n\n/cancel");

        } else if (data.startsWith("edit:")) {
            String[] parts = data.split(":", 3);
            if (parts.length < 3) return;
            String field = parts[1], code = parts[2];
            if (field.equals("cancel")) {
                states.put(uid, State.NONE); clearPending(uid);
                sendKb(chatId, "❌ Bekor qilindi.", adminKb(uid)); return;
            }
            pendingCode.put(uid, code);
            switch (field) {
                case "title" -> { states.put(uid, State.WAITING_EDIT_VALUE); pendingData.put(uid, "title"); sendText(chatId, "✏️ Yangi nomini kiriting:\n/cancel"); }
                case "code"  -> { states.put(uid, State.WAITING_EDIT_VALUE); pendingData.put(uid, "code");  sendText(chatId, "🔑 Yangi kodini kiriting:\n/cancel"); }
                case "vids"  -> {
                    Movie mv = movies.get(code);
                    pendingTitle.put(uid, mv != null ? mv.title() : code);
                    pendingVids.put(uid, new ArrayList<>());
                    states.put(uid, State.WAITING_EDIT_VIDEOS);
                    sendText(chatId, "🎬 Hozirda <b>" + (mv != null ? mv.msgRefs().size() : 0) + "</b> ta qism.\n\nYangi videolarni forward qiling (kanaldan).\n/cancel");
                }
            }
        }
    }

    // ═══ ADMIN HANDLER ════════════════════════════════════
    private void handleAdmin(Message msg) {
        long aid     = msg.getFrom().getId();
        String uname = msg.getFrom().getUserName();
        String fname = msg.getFrom().getFirstName();
        String txt   = msg.hasText() ? msg.getText().trim() : "";
        State  st    = states.getOrDefault(aid, State.NONE);

        if (txt.startsWith("/start") || txt.equals("/admin") || txt.equals("👑 Admin panel")) {
            states.put(aid, State.NONE); clearPending(aid);
            sendKb(aid, buildAdminHeader(aid, fname), adminKb(aid)); return;
        }
        if (txt.equals("/cancel") || txt.equals("🚫 Bekor qilish")) {
            states.put(aid, State.NONE); clearPending(aid);
            sendKb(aid, "❌ Bekor qilindi.", adminKb(aid)); return;
        }
        if (txt.equals("/addmovie") || txt.equals("➕ Film qo'shish")) {
            states.put(aid, State.WAITING_CODE); pendingVids.put(aid, new ArrayList<>()); pendingTitle.remove(aid);
            sendText(aid, "🎬 <b>Film qo'shish</b>\n\n1️⃣ Film kodini kiriting:\n<code>Masalan: 0001</code>\n\n/cancel"); return;
        }
        if (txt.equals("❌ Film o'chirish") || txt.startsWith("/delmovie")) {
            if (txt.startsWith("/delmovie ")) {
                String code = txt.substring(10).trim().toUpperCase();
                sendKb(aid, dbDelMovie(code) ? "✅ <code>"+code+"</code> — o'chirildi!" : "❌ <code>"+code+"</code> — topilmadi!", adminKb(aid)); return;
            }
            states.put(aid, State.WAITING_DEL_CODE);
            sendText(aid, "❌ <b>Film o'chirish</b>\n\nKodini yuboring:\n/cancel"); return;
        }
        if (txt.equals("✏️ Film tahrirlash") || txt.equals("/editmovie")) {
            states.put(aid, State.WAITING_EDIT_CODE);
            sendText(aid, "✏️ <b>Film tahrirlash</b>\n\nFilm kodini yuboring:\n/cancel"); return;
        }
        if (txt.equals("/listmovies") || txt.equals("📋 Filmlar ro'yxati")) {
            sendMoviesList(aid); return;
        }
        if (txt.equals("/search") || txt.equals("🔍 Film qidirish")) {
            states.put(aid, State.WAITING_SEARCH);
            sendText(aid, "🔍 <b>Qidirish</b>\n\nNom yoki kodini yuboring:\n/cancel"); return;
        }
        if (txt.equals("/stats") || txt.equals("📊 Statistika")) {
            long totalParts = movies.values().stream().mapToLong(m -> m.msgRefs().size()).sum();
            sendText(aid, String.format(
                    "📊 <b>Statistika</b>\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "👥 Foydalanuvchilar: <b>%d</b>\n🎬 Filmlar: <b>%d</b>\n" +
                            "📽 Jami qismlar: <b>%d</b>\n📺 Kanallar: <b>%d</b>\n🛡 Adminlar: <b>%d</b>",
                    countUsers(), movies.size(), totalParts, channels.size(), dbListAdmins().size()+1)); return;
        }
        if (txt.equals("/broadcast") || txt.equals("📣 Reklama yuborish")) {
            states.put(aid, State.WAITING_BROADCAST);
            sendText(aid, "📣 <b>Reklama</b>\n\nXabarni yuboring:\n/cancel"); return;
        }
        if (txt.equals("📺 Kanallar") || txt.equals("/channels")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            showChannelsList(aid); return;
        }
        if (txt.equals("➕ Kanal qo'shish") || txt.equals("/addchannel")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            states.put(aid, State.WAITING_CH_NAME);
            sendText(aid, "📺 <b>Kanal qo'shish</b>\n\n1️⃣ Kanal nomini kiriting:\n/cancel"); return;
        }
        if (txt.equals("❌ Kanal o'chirish") || txt.equals("/delchannel")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            showChannelsDeleteMenu(aid); return;
        }
        if (txt.equals("👥 Adminlar") || txt.equals("/admins")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            showAdminsList(aid); return;
        }
        if (txt.equals("➕ Admin qo'shish") || txt.equals("/addadmin")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            states.put(aid, State.WAITING_ADD_ADMIN);
            sendText(aid, "👥 <b>Admin qo'shish</b>\n\nAdmin ID sini yuboring:\n/cancel"); return;
        }
        if (txt.equals("❌ Admin o'chirish") || txt.equals("/deladmin")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return; }
            showAdminsDeleteMenu(aid); return;
        }

        // ════ VIDEO KELDI (forward qilingan yoki to'g'ridan-to'g'ri) ════
        if ((msg.hasVideo() || msg.hasDocument()) &&
                (st == State.WAITING_VIDEOS || st == State.WAITING_EDIT_VIDEOS)) {

            String code = pendingCode.get(aid);
            if (code == null) { sendText(aid, "⚠️ Avval film kodini kiriting. /addmovie"); return; }

            // Film nomini birinchi xabar caption dan olamiz
            if (!pendingTitle.containsKey(aid)) {
                String cap = msg.getCaption();
                pendingTitle.put(aid, (cap != null && !cap.isBlank()) ? cap.trim() : code);
            }

            // ═══ ASOSIY QISM: msgRef ni aniqlash ═══
            // Forward qilingan xabar — asl manba ma'lumotlarini olamiz
            String msgRef = null;

            if (msg.getForwardFromChat() != null) {
                // Kanaldan forward — asl kanal chat_id va message_id
                long fromChatId = msg.getForwardFromChat().getId();
                int  fromMsgId  = msg.getForwardFromMessageId();
                msgRef = fromChatId + ":" + fromMsgId;
                System.out.println("📤 Forward from channel: " + fromChatId + ":" + fromMsgId);

            } else if (msg.getForwardFrom() != null) {
                // Odamdan forward — botning o'z chat_id va message_id ishlatiladi
                msgRef = aid + ":" + msg.getMessageId();
                System.out.println("📤 Forward from user, saving as: " + msgRef);

            } else {
                // To'g'ridan-to'g'ri yuborilgan — botning chat_id va message_id
                msgRef = aid + ":" + msg.getMessageId();
                System.out.println("📤 Direct video, saving as: " + msgRef);
            }

            List<String> refs = pendingVids.computeIfAbsent(aid, k -> new ArrayList<>());
            refs.add(msgRef);
            dbSaveMovie(code, pendingTitle.get(aid), new ArrayList<>(refs));

            sendText(aid, "✅ <b>" + refs.size() + "-qism saqlandi!</b>\n" +
                    "🔑 <code>" + code + "</code>\n" +
                    "📌 Ref: <code>" + msgRef + "</code>\n\n" +
                    "Yana video yuboring yoki /cancel");
            return;
        }

        // ════ REKLAMA XABARI KELDI ════
        if (st == State.WAITING_BROADCAST &&
                (msg.hasText() || msg.hasPhoto() || msg.hasVideo() || msg.hasDocument() || msg.hasAudio() || msg.hasVoice())) {
            states.put(aid, State.NONE);
            List<Long> uids = getAllUserIds();
            sendText(aid, "⏳ <b>Yuborilmoqda...</b> " + uids.size() + " foydalanuvchi");
            int ok = 0, fail = 0;
            for (Long uid : uids) {
                if (isAdmin(uid, null)) continue;
                try {
                    CopyMessage cp = new CopyMessage();
                    cp.setChatId(String.valueOf(uid)); cp.setFromChatId(String.valueOf(aid)); cp.setMessageId(msg.getMessageId());
                    execute(cp); ok++; sleep(50);
                } catch (Exception e) { fail++; }
            }
            int chOk = 0, chFail = 0;
            for (Long chId : BROADCAST_CHANNEL_IDS) {
                try {
                    CopyMessage cp = new CopyMessage();
                    cp.setChatId(String.valueOf(chId)); cp.setFromChatId(String.valueOf(aid)); cp.setMessageId(msg.getMessageId());
                    execute(cp); chOk++; sleep(500);
                } catch (Exception e) { chFail++; }
            }
            sendKb(aid, "✅ <b>Tugadi!</b>\n👥 ✅" + ok + " ❌" + fail + "\n📺 ✅" + chOk + " ❌" + chFail, adminKb(aid));
            return;
        }

        // ════ MATN HOLATLARI ════
        if (!msg.hasText() || txt.startsWith("/")) return;

        switch (st) {
            case WAITING_CODE -> {
                String code = txt.toUpperCase().replaceAll("[^A-Z0-9_\\-]", "");
                if (code.isEmpty()) { sendText(aid, "❌ Noto'g'ri kod!"); return; }
                pendingCode.put(aid, code); pendingVids.put(aid, new ArrayList<>()); pendingTitle.remove(aid);
                states.put(aid, State.WAITING_VIDEOS);
                sendText(aid,
                        "✅ Kod: <code>" + code + "</code>\n\n" +
                        "2️⃣ Videolarni forward qiling (kanaldan):\n\n" +
                        "📌 <b>Qanday qilish kerak:</b>\n" +
                        "• Kanalga borib, video xabarni tanlang\n" +
                        "• Forward (Yo'naltirish) ni bosing\n" +
                        "• Bu botga yo'naltiring\n\n" +
                        "⚡ Har video saqlanadi!\n/cancel");
            }
            case WAITING_VIDEOS, WAITING_EDIT_VIDEOS -> sendText(aid, "⚠️ Video forward qiling (kanaldan)!\n/cancel");
            case WAITING_DEL_CODE -> {
                String code = txt.toUpperCase().trim();
                sendKb(aid, dbDelMovie(code) ? "✅ <code>"+code+"</code> o'chirildi!" : "❌ <code>"+code+"</code> topilmadi!", adminKb(aid));
                states.put(aid, State.NONE);
            }
            case WAITING_SEARCH -> {
                states.put(aid, State.NONE);
                List<Movie> res = searchMoviesAdmin(txt);
                if (res.isEmpty()) { sendKb(aid, "❌ \"" + escHtml(txt) + "\" topilmadi.", adminKb(aid)); return; }
                StringBuilder sb = new StringBuilder("🔍 <b>Natijalar:</b>\n━━━━━━━━━━━━━━━━━━━━━━━━\n");
                int i = 1;
                for (Movie m : res) {
                    sb.append(i++).append(". <code>").append(m.code()).append("</code> — ").append(escHtml(m.title()));
                    if (m.msgRefs().size() > 1) sb.append(" (").append(m.msgRefs().size()).append("q)");
                    sb.append("\n");
                }
                sendKb(aid, sb.toString(), adminKb(aid));
            }
            case WAITING_CH_NAME -> {
                pendingData.put(aid, txt + "||");
                states.put(aid, State.WAITING_CH_ID);
                sendText(aid, "✅ Nom: <b>" + escHtml(txt) + "</b>\n\n2️⃣ Kanal ID:\n<code>-1001234567890</code>\n\n/cancel");
            }
            case WAITING_CH_ID -> {
                try {
                    long cid = Long.parseLong(txt.trim());
                    String name = pendingData.getOrDefault(aid, "Kanal||").split("\\|\\|")[0];
                    pendingData.put(aid, name + "||" + cid);
                    states.put(aid, State.WAITING_CH_TYPE);
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    InlineKeyboardButton b1 = new InlineKeyboardButton(); b1.setText("🔐 So'rovli (Request)"); b1.setCallbackData("ch_type:request");
                    InlineKeyboardButton b2 = new InlineKeyboardButton(); b2.setText("📢 Ochiq (Public)");     b2.setCallbackData("ch_type:public");
                    rows.add(List.of(b1)); rows.add(List.of(b2));
                    SendMessage m = new SendMessage(); m.setChatId(String.valueOf(aid));
                    m.setText("✅ ID: <code>" + cid + "</code>\n\n3️⃣ Kanal turini tanlang:");
                    m.setParseMode("HTML"); m.setReplyMarkup(new InlineKeyboardMarkup(rows)); execMsg(m);
                } catch (NumberFormatException e) { sendText(aid, "❌ Noto'g'ri ID!\n<code>-1001234567890</code>"); }
            }
            case WAITING_CH_LINK -> {
                String raw = pendingData.getOrDefault(aid, "||");
                String[] p = raw.split("\\|\\|", 3);
                String name   = p.length > 0 ? p[0] : "Kanal";
                long cid      = p.length > 1 && !p[1].isEmpty() ? Long.parseLong(p[1]) : 0L;
                boolean isReq = p.length > 2 && p[2].equals("request");
                dbAddChannel(name, cid, txt.trim(), isReq);
                sendKb(aid, "✅ <b>Kanal qo'shildi!</b>\n📺 " + escHtml(name) + "\n🔑 " + (isReq ? "🔐 So'rovli" : "📢 Ochiq"), adminKb(aid));
                states.put(aid, State.NONE); clearPending(aid);
            }
            case WAITING_ADD_ADMIN -> {
                try {
                    long newId = Long.parseLong(txt.trim());
                    if (newId == SUPER_ADMIN_ID) sendText(aid, "⚠️ Super admin allaqachon!");
                    else { dbAddAdmin(newId, null, aid); sendKb(aid, "✅ Admin: <code>" + newId + "</code>", adminKb(aid)); }
                } catch (NumberFormatException e) { sendText(aid, "❌ Noto'g'ri ID!"); }
                states.put(aid, State.NONE);
            }
            case WAITING_EDIT_CODE -> {
                String code = txt.toUpperCase().trim();
                Movie mv = movies.get(code);
                if (mv == null) { sendText(aid, "❌ <code>" + code + "</code> topilmadi."); return; }
                pendingCode.put(aid, code);
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                InlineKeyboardButton b1 = new InlineKeyboardButton(); b1.setText("✏️ Nomi");    b1.setCallbackData("edit:title:" + code);
                InlineKeyboardButton b2 = new InlineKeyboardButton(); b2.setText("🔑 Kodi");    b2.setCallbackData("edit:code:"  + code);
                InlineKeyboardButton b3 = new InlineKeyboardButton(); b3.setText("🎬 Videolar"); b3.setCallbackData("edit:vids:"  + code);
                InlineKeyboardButton b4 = new InlineKeyboardButton(); b4.setText("🚫 Bekor");   b4.setCallbackData("edit:cancel:" + code);
                rows.add(List.of(b1, b2)); rows.add(List.of(b3)); rows.add(List.of(b4));
                SendMessage m = new SendMessage(); m.setChatId(String.valueOf(aid));
                m.setText("✏️ <b>" + code + "</b> — " + escHtml(mv.title()) + "\n📽 " + mv.msgRefs().size() + " qism\n\nNimani tahrirlaysiz?");
                m.setParseMode("HTML"); m.setReplyMarkup(new InlineKeyboardMarkup(rows)); execMsg(m);
                states.put(aid, State.WAITING_EDIT_FIELD);
            }
            case WAITING_EDIT_VALUE -> {
                String field = pendingData.get(aid), code = pendingCode.get(aid);
                if (field != null && code != null) {
                    if (field.equals("title")) sendKb(aid, dbUpdateTitle(code, txt) ? "✅ Nom: <b>\""+escHtml(txt)+"\"</b>" : "❌ Xato!", adminKb(aid));
                    else if (field.equals("code")) {
                        String nc = txt.toUpperCase().replaceAll("[^A-Z0-9_\\-]", "");
                        sendKb(aid, dbUpdateCode(code, nc) ? "✅ <code>"+code+"</code> → <code>"+nc+"</code>" : "❌ Xato!", adminKb(aid));
                    }
                }
                states.put(aid, State.NONE); clearPending(aid);
            }
            default -> {}
        }
    }

    // ═══ FILMLAR RO'YXATI ════════════════════════════════
    private void sendMoviesList(long aid) {
        if (movies.isEmpty()) { sendText(aid, "📭 Bazada filmlar yo'q."); return; }

        List<Movie> sorted = movies.values().stream()
                .sorted(Comparator.comparing(Movie::code))
                .collect(Collectors.toList());
        int total = sorted.size();

        String firstHeader = "📋 <b>Filmlar ro'yxati</b> — jami <b>" + total + "</b> ta\n━━━━━━━━━━━━━━━━━━━━━━━━\n";
        String contHeader  = "📋 <b>Davomi...</b>\n━━━━━━━━━━━━━━━━━━━━━━━━\n";

        StringBuilder current = new StringBuilder(firstHeader);
        boolean isFirst = true;

        for (int i = 0; i < sorted.size(); i++) {
            Movie m = sorted.get(i);
            String line = (i + 1) + ". <code>" + m.code() + "</code> — " + escHtml(m.title());
            if (m.msgRefs().size() > 1) line += " <i>(" + m.msgRefs().size() + "q)</i>";
            line += "\n";

            if (current.length() + line.length() > MAX_MSG_LEN) {
                sendText(aid, current.toString().trim());
                sleep(150);
                current = new StringBuilder(contHeader);
                isFirst = false;
            }
            current.append(line);
        }

        if (current.length() > (isFirst ? firstHeader.length() : contHeader.length())) {
            sendText(aid, current.toString().trim());
        }
    }

    // ─── YORDAMCHI ──────────────────────────────────────
    private String buildAdminHeader(long uid, String fname) {
        return "╔══════════════════════════╗\n"
                + "║  " + (isSuperAdmin(uid) ? "👑 SUPER ADMIN" : "🛡 ADMIN") + "\n"
                + "║  👤 " + escHtml(fname != null ? fname : "Admin") + "\n"
                + "║  🆔 <code>" + uid + "</code>\n"
                + "╚══════════════════════════╝\n\nBo'limni tanlang:";
    }

    private void showChannelsList(long aid) {
        if (channels.isEmpty()) { sendText(aid, "📭 Kanallar yo'q."); return; }
        StringBuilder sb = new StringBuilder("📺 <b>Kanallar</b>\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        int i = 1;
        for (Channel ch : channels)
            sb.append(i++).append(". ").append(ch.hasJoinRequest() ? "🔐" : "📢")
                    .append(" <b>").append(escHtml(ch.name())).append("</b>\n")
                    .append("   🆔 <code>").append(ch.id()).append("</code>\n")
                    .append("   🔗 ").append(ch.link()).append("\n\n");
        sendText(aid, sb.toString().trim());
    }

    private void showChannelsDeleteMenu(long aid) {
        if (channels.isEmpty()) { sendText(aid, "📭 Kanal yo'q."); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Channel ch : channels) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText("❌ " + (ch.hasJoinRequest() ? "🔐" : "📢") + " " + ch.name());
            b.setCallbackData("del_ch:" + ch.id()); rows.add(List.of(b));
        }
        SendMessage m = new SendMessage(); m.setChatId(String.valueOf(aid));
        m.setText("📺 <b>Qaysi kanalni o'chirasiz?</b>"); m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows)); execMsg(m);
    }

    private void showAdminsList(long aid) {
        StringBuilder sb = new StringBuilder("👥 <b>Adminlar</b>\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("1. 👑 @").append(SUPER_ADMIN_USERNAME).append("\n   <code>").append(SUPER_ADMIN_ID).append("</code>\n\n");
        int i = 2;
        for (Long id : dbListAdmins()) sb.append(i++).append(". 🛡 <code>").append(id).append("</code>\n");
        sendText(aid, sb.toString().trim());
    }

    private void showAdminsDeleteMenu(long aid) {
        List<Long> list = dbListAdmins();
        if (list.isEmpty()) { sendKb(aid, "📭 Qo'shimcha adminlar yo'q.", adminKb(aid)); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Long id : list) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText("❌ Admin: " + id); b.setCallbackData("del_admin:" + id); rows.add(List.of(b));
        }
        SendMessage m = new SendMessage(); m.setChatId(String.valueOf(aid));
        m.setText("👥 <b>Qaysi adminni o'chirasiz?</b>"); m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows)); execMsg(m);
    }

    private void clearPending(long aid) {
        pendingCode.remove(aid); pendingTitle.remove(aid); pendingVids.remove(aid); pendingData.remove(aid);
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ─── KLAVIATURALAR ──────────────────────────────────
    private ReplyKeyboardMarkup userKb() {
        KeyboardRow r = new KeyboardRow(); r.add(new KeyboardButton("🎬 Film kodi"));
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(List.of(r)); kb.setResizeKeyboard(true); return kb;
    }

    private ReplyKeyboardMarkup adminKb(long uid) {
        List<KeyboardRow> rows = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add(new KeyboardButton("➕ Film qo'shish")); r1.add(new KeyboardButton("❌ Film o'chirish")); rows.add(r1);
        KeyboardRow r2 = new KeyboardRow(); r2.add(new KeyboardButton("✏️ Film tahrirlash")); r2.add(new KeyboardButton("📋 Filmlar ro'yxati")); rows.add(r2);
        KeyboardRow r3 = new KeyboardRow(); r3.add(new KeyboardButton("🔍 Film qidirish")); r3.add(new KeyboardButton("📊 Statistika")); rows.add(r3);
        KeyboardRow r4 = new KeyboardRow(); r4.add(new KeyboardButton("📣 Reklama yuborish")); r4.add(new KeyboardButton("👑 Admin panel")); rows.add(r4);
        if (isSuperAdmin(uid)) {
            KeyboardRow r5 = new KeyboardRow(); r5.add(new KeyboardButton("📺 Kanallar")); r5.add(new KeyboardButton("➕ Kanal qo'shish")); rows.add(r5);
            KeyboardRow r6 = new KeyboardRow(); r6.add(new KeyboardButton("❌ Kanal o'chirish")); r6.add(new KeyboardButton("➕ Admin qo'shish")); rows.add(r6);
            KeyboardRow r7 = new KeyboardRow(); r7.add(new KeyboardButton("👥 Adminlar")); r7.add(new KeyboardButton("❌ Admin o'chirish")); rows.add(r7);
        }
        KeyboardRow r8 = new KeyboardRow(); r8.add(new KeyboardButton("🚫 Bekor qilish")); rows.add(r8);
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(rows); kb.setResizeKeyboard(true); return kb;
    }

    // ─── XABAR YUBORISH ─────────────────────────────────
    private void sendText(long chatId, String text) {
        SendMessage m = new SendMessage(); m.setChatId(String.valueOf(chatId)); m.setText(text); m.setParseMode("HTML"); execMsg(m);
    }

    private void sendKb(long chatId, String text, ReplyKeyboard kb) {
        SendMessage m = new SendMessage(); m.setChatId(String.valueOf(chatId)); m.setText(text); m.setParseMode("HTML"); m.setReplyMarkup(kb); execMsg(m);
    }

    private void sendRaw(long chatId, String text) {
        try { SendMessage m = new SendMessage(); m.setChatId(String.valueOf(chatId)); m.setText(text); execute(m); }
        catch (TelegramApiException e) { System.err.println("sendRaw: " + e.getMessage()); }
    }

    private void execMsg(SendMessage m) {
        try { execute(m); } catch (TelegramApiException e) { System.err.println("execMsg: " + e.getMessage()); }
    }

    // ─── ISHGA TUSHIRISH ────────────────────────────────
    public static void main(String[] args) {
        System.out.println("🎬 KinoBot ishga tushmoqda...");
        try {
            Main bot = new Main();
            bot.initDb();
            bot.loadMoviesFromDb();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                bot.flushUsers(); bot.scheduler.shutdown();
                System.out.println("💾 Bot to'xtatildi.");
            }));
            new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
            System.out.println("✅ Bot ishga tushdi! @" + BOT_USERNAME);
        } catch (TelegramApiException e) { System.err.println("❌ " + e.getMessage()); }
    }
}
