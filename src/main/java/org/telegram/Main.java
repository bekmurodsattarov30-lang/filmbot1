package org.telegram;

import org.json.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.groupadministration.ApproveChatJoinRequest;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
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

    // Reklama yuboriladigan kanal IDlari — bot shu kanallarda ADMIN bo'lishi kerak
    private static final List<Long> BROADCAST_CHANNEL_IDS = List.of(
            -1003705247131L,
            -1003837469684L
    );

    // ════════════════════════════════════════════════════════
    //  Channel record: hasJoinRequest = true bo'lsa "request" kanal
    //  false bo'lsa public/oddiy kanal — a'zolikni status bilan tekshiramiz
    // ════════════════════════════════════════════════════════
    record Channel(String name, long id, String link, boolean hasJoinRequest) {}
    record Movie(String code, String title, List<String> fileIds) {}

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

    // ─── DB INIT ────────────────────────────────────────
    private void initDb() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            moviesJson   = loadJson("movies.json");
            usersJson    = loadJson("users.json");
            adminsJson   = loadJson("admins.json");
            channelsJson = loadJson("channels.json");
            loadChannelsFromDb();
            scheduler.scheduleAtFixedRate(this::flushUsers, 30, 30, TimeUnit.SECONDS);
            System.out.println("✅ JSON data tayyor: " + DATA_DIR);
        } catch (IOException e) {
            System.err.println("❌ initDb xato: " + e.getMessage());
        }
    }

    private void flushUsers() {
        if (!dirtyUsers.isEmpty()) {
            saveJson("users.json", usersJson);
            dirtyUsers.clear();
        }
    }

    private JSONObject loadJson(String filename) {
        try {
            Path p = Paths.get(DATA_DIR + filename);
            if (Files.exists(p)) return new JSONObject(Files.readString(p));
        } catch (Exception ignored) {}
        return new JSONObject();
    }

    private synchronized void saveJson(String filename, JSONObject data) {
        try {
            Files.writeString(Paths.get(DATA_DIR + filename),
                    data.toString(2),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("saveJson xato: " + e.getMessage());
        }
    }

    // ─── KANALLAR ───────────────────────────────────────
    private void loadChannelsFromDb() {
        if (channelsJson.isEmpty()) {
            // Boshlang'ich kanallarni saqlash
            for (Channel ch : channels)
                channelsJson.put(String.valueOf(ch.id()),
                        new JSONObject()
                                .put("name", ch.name())
                                .put("link", ch.link())
                                .put("hasJoinRequest", ch.hasJoinRequest()));
            saveJson("channels.json", channelsJson);
        } else {
            channels.clear();
            for (String cid : channelsJson.keySet()) {
                JSONObject o = channelsJson.getJSONObject(cid);
                boolean hjr = o.optBoolean("hasJoinRequest", false);
                channels.add(new Channel(
                        o.getString("name"),
                        Long.parseLong(cid),
                        o.getString("link"),
                        hjr
                ));
            }
        }
    }

    private boolean dbAddChannel(String name, long cid, String link, boolean hasJoinRequest) {
        channelsJson.put(String.valueOf(cid), new JSONObject()
                .put("name", name)
                .put("link", link)
                .put("hasJoinRequest", hasJoinRequest));
        saveJson("channels.json", channelsJson);
        channels.removeIf(c -> c.id() == cid);
        channels.add(new Channel(name, cid, link, hasJoinRequest));
        return true;
    }

    private boolean dbDelChannel(long cid) {
        String key = String.valueOf(cid);
        if (!channelsJson.has(key)) return false;
        channelsJson.remove(key);
        saveJson("channels.json", channelsJson);
        channels.removeIf(c -> c.id() == cid);
        return true;
    }

    // ─── ADMINLAR ───────────────────────────────────────
    private boolean isAdminInDb(long uid) { return adminsJson.has(String.valueOf(uid)); }

    private boolean dbAddAdmin(long id, String uname, long addedBy) {
        adminsJson.put(String.valueOf(id), new JSONObject()
                .put("username", uname != null ? uname : "").put("addedBy", addedBy));
        saveJson("admins.json", adminsJson);
        return true;
    }

    private boolean dbDelAdmin(long id) {
        String key = String.valueOf(id);
        if (!adminsJson.has(key)) return false;
        adminsJson.remove(key);
        saveJson("admins.json", adminsJson);
        return true;
    }

    private List<long[]> dbListAdmins() {
        List<long[]> list = new ArrayList<>();
        for (String key : adminsJson.keySet()) list.add(new long[]{Long.parseLong(key)});
        return list;
    }

    // ─── FILMLAR ────────────────────────────────────────
    private boolean dbSaveMovie(String code, String title, List<String> fileIds) {
        String t = (title != null && !title.isBlank()) ? title : code;
        moviesJson.put(code, new JSONObject().put("title", t).put("fileIds", new JSONArray(fileIds)));
        saveJson("movies.json", moviesJson);
        movies.put(code, new Movie(code, t, new ArrayList<>(fileIds)));
        System.out.println("✅ Saqlandi: " + code + " | " + t + " | " + fileIds.size() + " qism");
        return true;
    }

    private boolean dbDelMovie(String code) {
        if (!moviesJson.has(code)) return false;
        moviesJson.remove(code);
        saveJson("movies.json", moviesJson);
        movies.remove(code);
        return true;
    }

    private boolean dbUpdateTitle(String code, String newTitle) {
        if (!moviesJson.has(code)) return false;
        moviesJson.getJSONObject(code).put("title", newTitle);
        saveJson("movies.json", moviesJson);
        Movie mv = movies.get(code);
        if (mv != null) movies.put(code, new Movie(mv.code(), newTitle, mv.fileIds()));
        return true;
    }

    private boolean dbUpdateCode(String oldCode, String newCode) {
        if (!moviesJson.has(oldCode)) return false;
        JSONObject data = moviesJson.getJSONObject(oldCode);
        moviesJson.remove(oldCode);
        moviesJson.put(newCode, data);
        saveJson("movies.json", moviesJson);
        Movie mv = movies.remove(oldCode);
        if (mv != null) movies.put(newCode, new Movie(newCode, mv.title(), mv.fileIds()));
        return true;
    }

    private void loadMoviesFromDb() {
        movies.clear();
        for (String code : moviesJson.keySet()) {
            JSONObject o = moviesJson.getJSONObject(code);
            JSONArray arr = o.getJSONArray("fileIds");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
            movies.put(code, new Movie(code, o.getString("title"), ids));
        }
        System.out.println("✅ " + movies.size() + " ta film xotiraga yuklandi.");
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
        for (String key : usersJson.keySet()) ids.add(Long.parseLong(key));
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
        WAITING_CH_NAME, WAITING_CH_ID, WAITING_CH_LINK, WAITING_CH_TYPE,
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

    @Override
    public void onUpdateReceived(Update update) {
        try {
            // ════ ChatJoinRequest — so'rov qabul qilish ════
            if (update.hasChatJoinRequest()) {
                ChatJoinRequest req = update.getChatJoinRequest();
                long userId = req.getUser().getId();
                long chatId = req.getChat().getId();

                // Request ni avtomatik qabul qil
                ApproveChatJoinRequest a = new ApproveChatJoinRequest();
                a.setChatId(String.valueOf(chatId));
                a.setUserId(userId);
                try { execute(a); } catch (TelegramApiException ignored) {}

                // Foydalanuvchiga xabar yuborish — lekin a'zolikni tekshirmaymiz (request qabul qilindi)
                // Bu yerda alohida hech nima qilmaymiz — /start bosganda tekshiriladi
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

            if (isAdmin(userId, uname)) {
                handleAdmin(msg);
                return;
            }

            // Oddiy user uchun
            if (msg.hasText() && msg.getText().startsWith("/start")) {
                if (!subscribedAll(userId)) {
                    sendSubMsg(chatId, userId);
                } else {
                    sendWelcome(chatId, fname);
                }
                return;
            }

            if (!subscribedAll(userId)) { sendSubMsg(chatId, userId); return; }

            if (msg.hasText()) {
                String txt = msg.getText().trim();
                if (!txt.startsWith("/")) {
                    sendFilm(chatId, txt);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Xato: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    //  A'ZOLIK TEKSHIRUVI
    //  - hasJoinRequest=true  → request yuborilganini tekshiramiz
    //    (ChatJoinRequest kelganda approved bo'ladi, shuning uchun
    //     GetChatMember status "member" bo'ladi. Agar bot request ni
    //     approve qilgan bo'lsa status "member" bo'ladi.)
    //  - hasJoinRequest=false → oddiy GetChatMember tekshiruvi
    // ════════════════════════════════════════════════════════
    private boolean subscribedAll(long uid) {
        if (isAdmin(uid, null)) return true;
        for (Channel ch : channels) {
            if (!subscribed(uid, ch.id())) return false;
        }
        return true;
    }

    /**
     * Request yoki public kanal — ikkalasida ham GetChatMember ishlatamiz.
     * Request kanalida: foydalanuvchi request yuborib, bot approve qilgandan keyin
     * status "member" bo'ladi. Shuning uchun bir xil tekshiruv ishlaydi.
     */
    private boolean subscribed(long uid, long chId) {
        try {
            GetChatMember r = new GetChatMember();
            r.setChatId(String.valueOf(chId));
            r.setUserId(uid);
            String s = execute(r).getStatus();
            return s.equals("member") || s.equals("administrator")
                    || s.equals("creator") || s.equals("restricted");
        } catch (TelegramApiException e) {
            return false;
        }
    }

    // ════════════════════════════════════════════════════════
    //  OBUNA XABARI — chiroyli animatsiyali UI
    // ════════════════════════════════════════════════════════
    private void sendSubMsg(long chatId, long uid) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (Channel ch : channels) {
            boolean ok = subscribed(uid, ch.id());
            InlineKeyboardButton b = new InlineKeyboardButton();

            String typeIcon = ch.hasJoinRequest() ? "🔐" : "📢";
            String typeLabel = ch.hasJoinRequest() ? " (So'rov)" : "";

            if (ok) {
                b.setText("✅ " + ch.name());
                b.setCallbackData("noop");
            } else {
                b.setText(typeIcon + " " + ch.name() + typeLabel + " — Ulaning");
                b.setUrl(ch.link());
            }
            rows.add(List.of(b));
        }

        InlineKeyboardButton chk = new InlineKeyboardButton();
        chk.setText("🔄 Tekshirish");
        chk.setCallbackData("chk");
        rows.add(List.of(chk));

        String text =
                "🎬 <b>FILMIFY BOT</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "⛔ <b>Botdan foydalanish uchun\n" +
                        "quyidagi kanallarga uling!</b>\n\n" +
                        "📌 Har bir kanalga ulaning,\n" +
                        "so'ng <b>🔄 Tekshirish</b> tugmasini bosing:\n\n" +
                        "💡 <i>🔐 belgili kanallar — so'rov yuboring,\n" +
                        "bot avtomatik qabul qiladi!</i>";

        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(text);
        m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows));
        exec(m);
    }

    // Xush kelibsiz xabari
    private void sendWelcome(long chatId, String fname) {
        String text =
                "🎬 <b>FILMIFY BOT</b> ga xush kelibsiz!\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "👋 Salom, <b>" + escHtml(fname) + "</b>!\n\n" +
                        "🍿 <b>Qanday ishlaydi?</b>\n" +
                        "» Kanaldan film kodini oling\n" +
                        "» Kodni botga yuboring\n" +
                        "» Film darhol sizga keladi!\n\n" +
                        "🔍 <i>Film kodini kiriting:</i>\n" +
                        "<code>Masalan: 0001</code>";

        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(text);
        m.setParseMode("HTML");
        m.setReplyMarkup(userKb());
        exec(m);
    }

    // ─── CALLBACK ───────────────────────────────────────
    private void handleCallback(CallbackQuery cb) {
        long chatId  = cb.getMessage().getChatId();
        long uid     = cb.getFrom().getId();
        String uname = cb.getFrom().getUserName();
        String data  = cb.getData();
        try {
            AnswerCallbackQuery a = new AnswerCallbackQuery();
            a.setCallbackQueryId(cb.getId());
            execute(a);
        } catch (Exception ignored) {}

        if ("noop".equals(data)) return;

        if ("chk".equals(data)) {
            if (subscribedAll(uid)) {
                sendMsg(chatId,
                        "✅ <b>Zo'r! Barcha kanallarga ulangansiz!</b>\n\n" +
                                "🎬 Film kodini yuboring va tomosha boshlang!",
                        userKb());
            } else {
                sendSubMsg(chatId, uid);
            }
            return;
        }

        if (!isAdmin(uid, uname)) return;

        if (data.startsWith("del_ch:")) {
            long cid = Long.parseLong(data.split(":")[1]);
            sendText(chatId, dbDelChannel(cid)
                    ? "✅ <b>Kanal o'chirildi!</b>"
                    : "❌ Kanal topilmadi.");
        } else if (data.startsWith("del_admin:")) {
            long aid2 = Long.parseLong(data.split(":")[1]);
            if (aid2 == SUPER_ADMIN_ID) { sendText(chatId, "⛔ Super adminni o'chirib bo'lmaydi!"); return; }
            if (!isSuperAdmin(uid))     { sendText(chatId, "⛔ Faqat Super Admin!"); return; }
            sendText(chatId, dbDelAdmin(aid2)
                    ? "✅ <b>Admin o'chirildi!</b>"
                    : "❌ Admin topilmadi.");

            // ════ KANAL TURI TANLASH ════
        } else if (data.equals("ch_type:request")) {
            pendingData.put(uid, pendingData.getOrDefault(uid, "") + "|request");
            // pendingData format: "name|chid|request"
            String[] parts = pendingData.get(uid).split("\\|");
            if (parts.length >= 3) {
                String name = parts[0];
                long cid = Long.parseLong(parts[1]);
                // link ni WAITING_CH_LINK da so'raymiz
                pendingCode.put(uid, String.valueOf(cid));
                states.put(uid, State.WAITING_CH_LINK);
                sendText(chatId, "✅ Tur: 🔐 <b>So'rovli (Request)</b>\n\n4️⃣ Invite link yuboring:\n<code>https://t.me/+xxxxx</code>\n\n/cancel");
            }
        } else if (data.equals("ch_type:public")) {
            pendingData.put(uid, pendingData.getOrDefault(uid, "") + "|public");
            String[] parts = pendingData.get(uid).split("\\|");
            if (parts.length >= 3) {
                String name = parts[0];
                long cid = Long.parseLong(parts[1]);
                pendingCode.put(uid, String.valueOf(cid));
                states.put(uid, State.WAITING_CH_LINK);
                sendText(chatId, "✅ Tur: 📢 <b>Ochiq (Public)</b>\n\n4️⃣ Invite link yuboring:\n<code>https://t.me/+xxxxx</code>\n\n/cancel");
            }

        } else if (data.startsWith("edit:")) {
            String[] parts = data.split(":", 3);
            if (parts.length < 3) return;
            String field = parts[1], code = parts[2];
            if (field.equals("cancel")) {
                states.put(uid, State.NONE); clearPending(uid);
                sendMsg(chatId, "❌ Bekor qilindi.", adminKb(uid)); return;
            }
            pendingCode.put(uid, code);
            switch (field) {
                case "title" -> {
                    states.put(uid, State.WAITING_EDIT_VALUE);
                    pendingData.put(uid, "title");
                    sendText(chatId, "✏️ <b>Yangi nomini kiriting:</b>\n\n/cancel");
                }
                case "code"  -> {
                    states.put(uid, State.WAITING_EDIT_VALUE);
                    pendingData.put(uid, "code");
                    sendText(chatId, "🔑 <b>Yangi kodini kiriting:</b>\n\n/cancel");
                }
                case "vids"  -> {
                    Movie mv = movies.get(code);
                    pendingTitle.put(uid, mv != null ? mv.title() : code);
                    pendingVids.put(uid, new ArrayList<>());
                    states.put(uid, State.WAITING_EDIT_VIDEOS);
                    sendText(chatId, "🎬 <b>Video almashtirish</b>\n\n" +
                            "Hozirda: <b>" + (mv != null ? mv.fileIds().size() : 0) + "</b> ta qism\n\n" +
                            "📥 Yangi videolarni forward qiling.\n" +
                            "⚡ Har video kelganda avtomatik saqlanadi!\n\n/cancel");
                }
            }
        }
    }

    // ─── FILM YUBORISH ──────────────────────────────────
    private void sendFilm(long chatId, String code) {
        Movie mv = movies.get(code.toUpperCase());
        if (mv == null) {
            sendText(chatId,
                    "❌ <b>Film topilmadi!</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "📌 <i>Kodni Instagram yoki Telegram\n" +
                            "kanalimizdan oling va to'g'ri kiriting.</i>\n\n" +
                            "💡 Kod: <code>" + escHtml(code.toUpperCase()) + "</code>");
            return;
        }
        int total = mv.fileIds().size();
        if (total > 1) {
            sendText(chatId,
                    "🎬 <b>" + escHtml(mv.title()) + "</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "📽 Jami <b>" + total + "</b> qism yuborilmoqda...\n" +
                            "⏳ <i>Biroz kuting...</i>");
        }
        for (int i = 0; i < mv.fileIds().size(); i++) {
            String cap = total == 1
                    ? "🎬 <b>" + escHtml(mv.title()) + "</b>\n\n🤖 @" + BOT_USERNAME
                    : "🎬 <b>" + escHtml(mv.title()) + "</b>\n" +
                    "📌 <b>" + (i+1) + "-qism</b> / " + total + "\n\n🤖 @" + BOT_USERNAME;
            SendVideo v = new SendVideo();
            v.setChatId(String.valueOf(chatId));
            v.setVideo(new InputFile(mv.fileIds().get(i)));
            v.setCaption(cap);
            v.setParseMode("HTML");
            try { execute(v); } catch (TelegramApiException e) {
                System.err.println("video xato: " + e.getMessage());
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════
    //  ADMIN HANDLER
    // ════════════════════════════════════════════════════════
    private boolean handleAdmin(Message msg) {
        long aid     = msg.getFrom().getId();
        String uname = msg.getFrom().getUserName();
        String fname = msg.getFrom().getFirstName();
        String txt   = msg.hasText() ? msg.getText().trim() : "";
        State  st    = states.getOrDefault(aid, State.NONE);

        if (txt.startsWith("/start") || txt.equals("/admin") || txt.equals("👑 Admin panel")) {
            states.put(aid, State.NONE); clearPending(aid);
            sendMsg(aid, buildAdminHeader(aid, fname), adminKb(aid)); return true;
        }
        if (txt.equals("/myid")) {
            sendText(aid, "🆔 ID: <code>" + aid + "</code>\n👤 @" + (uname != null ? uname : "yo'q")
                    + "\n🏅 " + (isSuperAdmin(aid) ? "👑 Super Admin" : "🛡 Admin")); return true;
        }
        if (txt.equals("/addmovie") || txt.equals("➕ Film qo'shish")) {
            states.put(aid, State.WAITING_CODE);
            pendingVids.put(aid, new ArrayList<>());
            pendingTitle.remove(aid);
            sendText(aid,
                    "🎬 <b>Film qo'shish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "1️⃣ Film kodini kiriting:\n" +
                            "<i>Masalan: <code>0001</code></i>\n\n/cancel");
            return true;
        }
        if (txt.equals("❌ Film o'chirish") || txt.startsWith("/delmovie")) {
            if (txt.startsWith("/delmovie ")) {
                String code = txt.substring(10).trim().toUpperCase();
                sendMsg(aid,
                        dbDelMovie(code)
                                ? "✅ <code>"+code+"</code> — o'chirildi!"
                                : "❌ <code>"+code+"</code> — topilmadi!",
                        adminKb(aid));
                return true;
            }
            states.put(aid, State.WAITING_DEL_CODE);
            sendText(aid,
                    "❌ <b>Film o'chirish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "Film kodini yuboring:\n\n/cancel");
            return true;
        }
        if (txt.equals("✏️ Film tahrirlash") || txt.equals("/editmovie")) {
            states.put(aid, State.WAITING_EDIT_CODE);
            sendText(aid,
                    "✏️ <b>Film tahrirlash</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "Film kodini yuboring:\n\n/cancel");
            return true;
        }
        if (txt.equals("/listmovies") || txt.equals("📋 Filmlar ro'yxati")) {
            sendMoviesList(aid); return true;
        }
        if (txt.equals("/search") || txt.equals("🔍 Film qidirish")) {
            states.put(aid, State.WAITING_SEARCH);
            sendText(aid,
                    "🔍 <b>Film qidirish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "Nom yoki kodini yuboring:\n\n/cancel");
            return true;
        }
        if (txt.equals("/stats") || txt.equals("📊 Statistika")) {
            sendStats(aid); return true;
        }
        if (txt.equals("/broadcast") || txt.equals("📣 Reklama yuborish")) {
            states.put(aid, State.WAITING_BROADCAST);
            sendText(aid,
                    "📣 <b>Reklama yuborish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "Xabarni yuboring (matn, rasm, video):\n\n/cancel");
            return true;
        }
        if (txt.equals("📺 Kanallar") || txt.equals("/channels")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            showChannelsList(aid); return true;
        }
        if (txt.equals("➕ Kanal qo'shish") || txt.equals("/addchannel")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            states.put(aid, State.WAITING_CH_NAME);
            sendText(aid,
                    "📺 <b>Kanal qo'shish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "1️⃣ Kanal nomini kiriting:\n\n/cancel");
            return true;
        }
        if (txt.equals("❌ Kanal o'chirish") || txt.equals("/delchannel")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            showChannelsDeleteMenu(aid); return true;
        }
        if (txt.equals("👥 Adminlar") || txt.equals("/admins")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            showAdminsList(aid); return true;
        }
        if (txt.equals("➕ Admin qo'shish") || txt.equals("/addadmin")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            states.put(aid, State.WAITING_ADD_ADMIN);
            sendText(aid,
                    "👥 <b>Admin qo'shish</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "Admin Telegram ID sini yuboring:\n\n/cancel");
            return true;
        }
        if (txt.equals("❌ Admin o'chirish") || txt.equals("/deladmin")) {
            if (!isSuperAdmin(aid)) { sendText(aid, "⛔ Faqat Super Admin!"); return true; }
            showAdminsDeleteMenu(aid); return true;
        }
        if (txt.equals("/cancel") || txt.equals("🚫 Bekor qilish")) {
            states.put(aid, State.NONE); clearPending(aid);
            sendMsg(aid, "❌ Bekor qilindi.", adminKb(aid)); return true;
        }

        // ════ VIDEO KELDI ════
        if (msg.hasVideo() && (st == State.WAITING_VIDEOS || st == State.WAITING_EDIT_VIDEOS)) {
            String fid  = msg.getVideo().getFileId();
            String code = pendingCode.get(aid);
            if (code == null) { sendText(aid, "⚠️ Avval film kodini kiriting. /addmovie"); return true; }
            if (!pendingTitle.containsKey(aid)) {
                String cap = msg.getCaption();
                pendingTitle.put(aid, (cap != null && !cap.isBlank()) ? cap.trim() : code);
            }
            List<String> vids = pendingVids.computeIfAbsent(aid, k -> new ArrayList<>());
            vids.add(fid);
            dbSaveMovie(code, pendingTitle.get(aid), new ArrayList<>(vids));
            sendText(aid,
                    "✅ <b>" + vids.size() + "-qism saqlandi!</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                            "🔑 Kod: <code>" + code + "</code>\n" +
                            "🎬 Nomi: <b>" + escHtml(pendingTitle.get(aid)) + "</b>\n" +
                            "📽 Jami: <b>" + vids.size() + "</b> ta qism\n\n" +
                            "📌 Yana video forward qiling yoki /cancel");
            return true;
        }

        // ════ MATN HOLATLARI ════
        if (msg.hasText() && !txt.startsWith("/")) {
            if (st == State.WAITING_CODE) {
                String code = txt.toUpperCase().replaceAll("[^A-Z0-9_\\-]", "");
                if (code.isEmpty()) { sendText(aid, "❌ Noto'g'ri kod! Faqat harf va raqam."); return true; }
                pendingCode.put(aid, code);
                pendingVids.put(aid, new ArrayList<>());
                pendingTitle.remove(aid);
                states.put(aid, State.WAITING_VIDEOS);
                sendText(aid,
                        "✅ Kod: <code>" + code + "</code>\n\n" +
                                "2️⃣ Filmni forward qiling:\n" +
                                "<i>💡 Birinchi videoning caption'i film nomi bo'ladi.</i>\n\n" +
                                "⚡ Har video kelganda avtomatik saqlanadi!\n\n/cancel");
                return true;
            }
            if (st == State.WAITING_VIDEOS || st == State.WAITING_EDIT_VIDEOS) {
                sendText(aid, "⚠️ Video fayl forward qiling!\n/cancel"); return true;
            }
            if (st == State.WAITING_DEL_CODE) {
                String code = txt.toUpperCase().trim();
                sendMsg(aid,
                        dbDelMovie(code)
                                ? "✅ <code>"+code+"</code> — o'chirildi!"
                                : "❌ <code>"+code+"</code> — topilmadi!",
                        adminKb(aid));
                states.put(aid, State.NONE); return true;
            }
            if (st == State.WAITING_SEARCH) {
                states.put(aid, State.NONE);
                List<Movie> res = searchMoviesAdmin(txt);
                if (res.isEmpty()) {
                    sendMsg(aid, "❌ <b>\"" + escHtml(txt) + "\"</b> topilmadi.", adminKb(aid));
                } else {
                    StringBuilder sb = new StringBuilder(
                            "🔍 <b>Qidiruv natijalari:</b>\n" +
                                    "━━━━━━━━━━━━━━━━━━━━━━━\n");
                    int i = 1;
                    for (Movie m : res) {
                        sb.append(i++).append(". 🎬 <code>").append(m.code()).append("</code> — ").append(escHtml(m.title()));
                        if (m.fileIds().size() > 1) sb.append(" (").append(m.fileIds().size()).append(" qism)");
                        sb.append("\n");
                    }
                    sendMsg(aid, sb.toString(), adminKb(aid));
                }
                return true;
            }

            // ════ KANAL QO'SHISH ════
            if (st == State.WAITING_CH_NAME) {
                pendingData.put(aid, txt); // faqat nom
                states.put(aid, State.WAITING_CH_ID);
                sendText(aid, "✅ Nom: <b>" + escHtml(txt) + "</b>\n\n2️⃣ Kanal ID:\n<code>-1001234567890</code>\n\n/cancel");
                return true;
            }
            if (st == State.WAITING_CH_ID) {
                try {
                    long cid = Long.parseLong(txt.trim());
                    // pendingData: "name|cid"
                    String name = pendingData.getOrDefault(aid, "Kanal");
                    pendingData.put(aid, name + "|" + cid);
                    states.put(aid, State.WAITING_CH_TYPE);

                    // Kanal turini tanlash tugmalari
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    InlineKeyboardButton b1 = new InlineKeyboardButton();
                    b1.setText("🔐 So'rovli kanal (Request)");
                    b1.setCallbackData("ch_type:request");
                    InlineKeyboardButton b2 = new InlineKeyboardButton();
                    b2.setText("📢 Ochiq kanal (Public)");
                    b2.setCallbackData("ch_type:public");
                    rows.add(List.of(b1));
                    rows.add(List.of(b2));
                    SendMessage m = new SendMessage();
                    m.setChatId(String.valueOf(aid));
                    m.setText("✅ ID: <code>" + cid + "</code>\n\n3️⃣ <b>Kanal turini tanlang:</b>\n\n" +
                            "🔐 <b>So'rovli</b> — foydalanuvchi so'rov yuboradi, bot qabul qiladi\n" +
                            "📢 <b>Ochiq</b> — oddiy a'zolik tugmasi");
                    m.setParseMode("HTML");
                    m.setReplyMarkup(new InlineKeyboardMarkup(rows));
                    exec(m);
                } catch (NumberFormatException e) {
                    sendText(aid, "❌ Noto'g'ri ID!\nMasalan: <code>-1001234567890</code>");
                }
                return true;
            }
            if (st == State.WAITING_CH_LINK) {
                String rawData = pendingData.getOrDefault(aid, "||");
                String[] parts = rawData.split("\\|");
                String name = parts.length > 0 ? parts[0] : "Kanal";
                long cid = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : 0;
                boolean isRequest = parts.length > 2 && parts[2].equals("request");
                dbAddChannel(name, cid, txt.trim(), isRequest);
                sendMsg(aid,
                        "✅ <b>Kanal qo'shildi!</b>\n" +
                                "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                                "📺 " + escHtml(name) + "\n" +
                                "🆔 <code>" + cid + "</code>\n" +
                                "🔑 Tur: " + (isRequest ? "🔐 So'rovli" : "📢 Ochiq"),
                        adminKb(aid));
                states.put(aid, State.NONE);
                clearPending(aid);
                return true;
            }

            if (st == State.WAITING_ADD_ADMIN) {
                try {
                    long newId = Long.parseLong(txt.trim());
                    if (newId == SUPER_ADMIN_ID) sendText(aid, "⚠️ Super admin allaqachon!");
                    else {
                        dbAddAdmin(newId, null, aid);
                        sendMsg(aid, "✅ <b>Admin qo'shildi!</b>\n🆔 <code>" + newId + "</code>", adminKb(aid));
                    }
                } catch (NumberFormatException e) { sendText(aid, "❌ Noto'g'ri ID!"); }
                states.put(aid, State.NONE); return true;
            }
            if (st == State.WAITING_EDIT_CODE) {
                String code = txt.toUpperCase().trim();
                Movie mv = movies.get(code);
                if (mv == null) {
                    sendText(aid, "❌ <code>" + code + "</code> topilmadi. Qayta kiriting:");
                    return true;
                }
                pendingCode.put(aid, code);
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                InlineKeyboardButton b1 = new InlineKeyboardButton();
                b1.setText("✏️ Nomini o'zgartirish"); b1.setCallbackData("edit:title:" + code);
                InlineKeyboardButton b2 = new InlineKeyboardButton();
                b2.setText("🔑 Kodini o'zgartirish"); b2.setCallbackData("edit:code:" + code);
                InlineKeyboardButton b3 = new InlineKeyboardButton();
                b3.setText("🎬 Videolarni almashtirish"); b3.setCallbackData("edit:vids:" + code);
                InlineKeyboardButton b4 = new InlineKeyboardButton();
                b4.setText("🚫 Bekor qilish"); b4.setCallbackData("edit:cancel:" + code);
                rows.add(List.of(b1, b2)); rows.add(List.of(b3)); rows.add(List.of(b4));
                SendMessage m = new SendMessage();
                m.setChatId(String.valueOf(aid));
                m.setText("✏️ <b>Tahrirlash</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                        "🔑 Kod: <code>" + code + "</code>\n" +
                        "🎬 Nomi: <b>" + escHtml(mv.title()) + "</b>\n" +
                        "📽 Qismlar: <b>" + mv.fileIds().size() + "</b> ta\n\n" +
                        "Nimani tahrirlaysiz?");
                m.setParseMode("HTML");
                m.setReplyMarkup(new InlineKeyboardMarkup(rows));
                exec(m);
                states.put(aid, State.WAITING_EDIT_FIELD);
                return true;
            }
            if (st == State.WAITING_EDIT_VALUE) {
                String field = pendingData.get(aid), code = pendingCode.get(aid);
                if (field != null && code != null) {
                    if (field.equals("title"))
                        sendMsg(aid,
                                dbUpdateTitle(code, txt)
                                        ? "✅ <b>Nom yangilandi!</b>\n🎬 \"" + escHtml(txt) + "\""
                                        : "❌ Xatolik!",
                                adminKb(aid));
                    else if (field.equals("code")) {
                        String nc = txt.toUpperCase().replaceAll("[^A-Z0-9_\\-]", "");
                        sendMsg(aid,
                                dbUpdateCode(code, nc)
                                        ? "✅ <b>Kod yangilandi!</b>\n<code>" + code + "</code> → <code>" + nc + "</code>"
                                        : "❌ Xatolik!",
                                adminKb(aid));
                    }
                }
                states.put(aid, State.NONE); clearPending(aid); return true;
            }
        }

        // ════ REKLAMA ════
        if (st == State.WAITING_BROADCAST && (msg.hasText() || msg.hasPhoto() || msg.hasVideo()
                || msg.hasDocument() || msg.hasAudio() || msg.hasVoice())) {
            states.put(aid, State.NONE);
            List<Long> uids = getAllUserIds();
            sendText(aid, "⏳ <b>Reklama yuborilmoqda...</b>\n👥 " + uids.size() + " ta foydalanuvchi");

            int ok = 0, fail = 0;
            for (Long uid : uids) {
                if (isAdmin(uid, null)) continue;
                try {
                    CopyMessage cp = new CopyMessage();
                    cp.setChatId(String.valueOf(uid));
                    cp.setFromChatId(String.valueOf(aid));
                    cp.setMessageId(msg.getMessageId());
                    execute(cp);
                    ok++;
                    Thread.sleep(50);
                } catch (Exception e) { fail++; }
            }

            int chOk = 0, chFail = 0;
            for (Long chId : BROADCAST_CHANNEL_IDS) {
                try {
                    CopyMessage cp = new CopyMessage();
                    cp.setChatId(String.valueOf(chId));
                    cp.setFromChatId(String.valueOf(aid));
                    cp.setMessageId(msg.getMessageId());
                    execute(cp);
                    chOk++;
                    Thread.sleep(500);
                } catch (Exception e) {
                    chFail++;
                    System.err.println("Kanal reklama xato " + chId + ": " + e.getMessage());
                }
            }

            sendMsg(aid,
                    "✅ <b>Reklama tugadi!</b>\n" +
                            "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                            "👥 Foydalanuvchilar:\n" +
                            "   ✅ <b>" + ok + "</b> ta yuborildi\n" +
                            "   ❌ <b>" + fail + "</b> ta yuborilmadi\n\n" +
                            "📺 Kanallar:\n" +
                            "   ✅ <b>" + chOk + "</b> ta yuborildi\n" +
                            "   ❌ <b>" + chFail + "</b> ta yuborilmadi",
                    adminKb(aid));
            return true;
        }

        return false;
    }

    // ─── YORDAMCHI METODLAR ─────────────────────────────
    private String buildAdminHeader(long uid, String fname) {
        return "╔══════════════════════════╗\n"
                + "║  " + (isSuperAdmin(uid) ? "👑 SUPER ADMIN" : "🛡 ADMIN") + "\n"
                + "║  👤 " + escHtml(fname != null ? fname : "Admin") + "\n"
                + "║  🆔 <code>" + uid + "</code>\n"
                + "╚══════════════════════════╝\n\n"
                + "🎬 <b>FILMIFY BOT</b> — Admin panel\n"
                + "Quyidagi bo'limlardan birini tanlang:";
    }

    private void sendMoviesList(long aid) {
        if (movies.isEmpty()) { sendText(aid, "📭 Bazada filmlar yo'q."); return; }

        List<Movie> sorted = movies.values().stream()
                .sorted(Comparator.comparing(Movie::code))
                .collect(Collectors.toList());

        // Sahifalar bo'yicha: 30 tadan
        int pageSize = 30;
        int total = sorted.size();
        int pages = (int) Math.ceil((double) total / pageSize);

        // Birinchi sahifani yuborish
        for (int p = 0; p < pages; p++) {
            StringBuilder sb = new StringBuilder();
            if (pages > 1) {
                sb.append("📋 <b>Filmlar ro'yxati</b> (").append(p+1).append("/").append(pages).append(" sahifa)\n");
            } else {
                sb.append("📋 <b>Filmlar ro'yxati</b>\n");
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("📊 Jami: <b>").append(total).append("</b> ta film\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━\n\n");

            int start = p * pageSize;
            int end   = Math.min(start + pageSize, total);
            for (int i = start; i < end; i++) {
                Movie m = sorted.get(i);
                sb.append(i+1).append(". <code>").append(m.code()).append("</code>");
                sb.append(" — ").append(escHtml(m.title()));
                if (m.fileIds().size() > 1) sb.append(" <i>(").append(m.fileIds().size()).append(" qism)</i>");
                sb.append("\n");
            }

            sendText(aid, sb.toString());

            if (p < pages - 1) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void sendStats(long aid) {
        long totalParts = movies.values().stream().mapToLong(m -> m.fileIds().size()).sum();
        sendText(aid, String.format(
                "📊 <b>Bot Statistikasi</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n\n" +
                        "👥 Foydalanuvchilar: <b>%d</b> ta\n" +
                        "🎬 Filmlar: <b>%d</b> ta\n" +
                        "📽 Jami qismlar: <b>%d</b> ta\n" +
                        "📺 Kanallar: <b>%d</b> ta\n" +
                        "🛡 Adminlar: <b>%d</b> ta\n\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n" +
                        "💾 Data: <code>%s</code>\n" +
                        "🤖 @%s",
                countUsers(), movies.size(), totalParts,
                channels.size(), dbListAdmins().size() + 1,
                DATA_DIR, BOT_USERNAME));
    }

    private void showChannelsList(long aid) {
        if (channels.isEmpty()) { sendText(aid, "📭 Kanallar yo'q."); return; }
        StringBuilder sb = new StringBuilder(
                "📺 <b>Kanallar ro'yxati</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        int i = 1;
        for (Channel ch : channels) {
            sb.append(i++).append(". ")
                    .append(ch.hasJoinRequest() ? "🔐" : "📢")
                    .append(" <b>").append(escHtml(ch.name())).append("</b>\n")
                    .append("   🆔 <code>").append(ch.id()).append("</code>\n")
                    .append("   🔗 ").append(ch.link()).append("\n")
                    .append("   📌 Tur: ").append(ch.hasJoinRequest() ? "<i>So'rovli</i>" : "<i>Ochiq</i>").append("\n\n");
        }
        sendText(aid, sb.toString());
    }

    private void showChannelsDeleteMenu(long aid) {
        if (channels.isEmpty()) { sendText(aid, "📭 Kanal yo'q."); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Channel ch : channels) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            String icon = ch.hasJoinRequest() ? "🔐" : "📢";
            b.setText("❌ " + icon + " " + ch.name());
            b.setCallbackData("del_ch:" + ch.id());
            rows.add(List.of(b));
        }
        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(aid));
        m.setText("📺 <b>Qaysi kanalni o'chirasiz?</b>");
        m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows));
        exec(m);
    }

    private void showAdminsList(long aid) {
        StringBuilder sb = new StringBuilder(
                "👥 <b>Adminlar ro'yxati</b>\n" +
                        "━━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("1. 👑 @").append(SUPER_ADMIN_USERNAME)
                .append("\n   🆔 <code>").append(SUPER_ADMIN_ID).append("</code>\n\n");
        int i = 2;
        for (long[] a : dbListAdmins()) {
            sb.append(i++).append(". 🛡 <code>").append(a[0]).append("</code>\n");
        }
        sb.append("\n📊 Jami: <b>").append(dbListAdmins().size() + 1).append("</b> ta admin");
        sendText(aid, sb.toString());
    }

    private void showAdminsDeleteMenu(long aid) {
        List<long[]> list = dbListAdmins();
        if (list.isEmpty()) { sendMsg(aid, "📭 Qo'shimcha adminlar yo'q.", adminKb(aid)); return; }
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (long[] a : list) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText("❌ Admin: " + a[0]);
            b.setCallbackData("del_admin:" + a[0]);
            rows.add(List.of(b));
        }
        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(aid));
        m.setText("👥 <b>Qaysi adminni o'chirasiz?</b>");
        m.setParseMode("HTML");
        m.setReplyMarkup(new InlineKeyboardMarkup(rows));
        exec(m);
    }

    private void clearPending(long aid) {
        pendingCode.remove(aid);
        pendingTitle.remove(aid);
        pendingVids.remove(aid);
        pendingData.remove(aid);
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    // ─── KLAVIATURALAR ──────────────────────────────────
    private ReplyKeyboardMarkup userKb() {
        KeyboardRow r1 = new KeyboardRow();
        r1.add(new KeyboardButton("🔍 Film qidirish"));
        r1.add(new KeyboardButton("🎬 Filmlar katalogi"));
        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(List.of(r1));
        kb.setResizeKeyboard(true);
        return kb;
    }

    private ReplyKeyboardMarkup adminKb(long uid) {
        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow r1 = new KeyboardRow();
        r1.add(new KeyboardButton("➕ Film qo'shish"));
        r1.add(new KeyboardButton("❌ Film o'chirish"));
        rows.add(r1);

        KeyboardRow r2 = new KeyboardRow();
        r2.add(new KeyboardButton("✏️ Film tahrirlash"));
        r2.add(new KeyboardButton("📋 Filmlar ro'yxati"));
        rows.add(r2);

        KeyboardRow r3 = new KeyboardRow();
        r3.add(new KeyboardButton("🔍 Film qidirish"));
        r3.add(new KeyboardButton("📊 Statistika"));
        rows.add(r3);

        KeyboardRow r4 = new KeyboardRow();
        r4.add(new KeyboardButton("📣 Reklama yuborish"));
        r4.add(new KeyboardButton("👑 Admin panel"));
        rows.add(r4);

        if (isSuperAdmin(uid)) {
            KeyboardRow r5 = new KeyboardRow();
            r5.add(new KeyboardButton("📺 Kanallar"));
            r5.add(new KeyboardButton("➕ Kanal qo'shish"));
            rows.add(r5);

            KeyboardRow r6 = new KeyboardRow();
            r6.add(new KeyboardButton("❌ Kanal o'chirish"));
            r6.add(new KeyboardButton("➕ Admin qo'shish"));
            rows.add(r6);

            KeyboardRow r7 = new KeyboardRow();
            r7.add(new KeyboardButton("👥 Adminlar"));
            r7.add(new KeyboardButton("❌ Admin o'chirish"));
            rows.add(r7);
        }

        KeyboardRow r8 = new KeyboardRow();
        r8.add(new KeyboardButton("🚫 Bekor qilish"));
        rows.add(r8);

        ReplyKeyboardMarkup kb = new ReplyKeyboardMarkup(rows);
        kb.setResizeKeyboard(true);
        return kb;
    }

    private void sendText(long chatId, String text) {
        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(text);
        m.setParseMode("HTML");
        exec(m);
    }

    private void sendMsg(long chatId, String text, ReplyKeyboard kb) {
        SendMessage m = new SendMessage();
        m.setChatId(String.valueOf(chatId));
        m.setText(text);
        m.setParseMode("HTML");
        m.setReplyMarkup(kb);
        exec(m);
    }

    private void exec(BotApiMethod<?> method) {
        try { execute(method); }
        catch (TelegramApiException e) { System.err.println("exec xato: " + e.getMessage()); }
    }

    // ─── ISHGA TUSHIRISH ────────────────────────────────
    public static void main(String[] args) {
        System.out.println("🎬 KinoBot ishga tushmoqda...");
        System.out.println("💾 Data papkasi: " + DATA_DIR);
        try {
            Main bot = new Main();
            bot.initDb();
            bot.loadMoviesFromDb();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                bot.flushUsers();
                bot.scheduler.shutdown();
                System.out.println("💾 Ma'lumotlar saqlandi. Bot to'xtatildi.");
            }));
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);
            System.out.println("✅ KinoBot ishga tushdi! @" + BOT_USERNAME);
        } catch (TelegramApiException e) {
            System.err.println("❌ Xato: " + e.getMessage());
        }
    }
}
