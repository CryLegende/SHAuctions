package org.Cry_Legende.sHAuctions.database;

import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.BidOutcome;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.BidStatus;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.Settlement;
import org.Cry_Legende.sHAuctions.database.impl.MariaAuctionDAO.SettleKind;
import org.Cry_Legende.sHAuctions.database.impl.MariaExpiredItemDAO;
import org.Cry_Legende.sHAuctions.model.AuctionItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Règles « une seule fois » du mode réseau, sur un vrai MariaDB et avec de vraies courses entre fils (chaque fil
 * joue le rôle d'un serveur). Ignoré sans SHAU_TEST_URL (base jetable : les tables shauctions_* y sont recréées),
 * SHAU_TEST_USER et SHAU_TEST_PASSWORD.
 */
class SharedDatabaseTest {

    private static final Logger LOG = Logger.getLogger("SharedDatabaseTest");
    private static final String[] TABLES = {"shauctions_auctions", "shauctions_sold_items", "shauctions_expired_items", "shauctions_wanted",
        "shauctions_bids", "shauctions_state"};

    private SharedDatabase db;
    private MariaAuctionDAO auctions;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("SHAU_TEST_URL");
        Assumptions.assumeTrue(url != null, "SHAU_TEST_URL absent : test MariaDB ignoré");
        db = new SharedDatabase(LOG, url, System.getenv("SHAU_TEST_USER"), System.getenv("SHAU_TEST_PASSWORD"), 5000);
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            for (String table : TABLES) {
                st.executeUpdate("DROP TABLE IF EXISTS " + table);
            }
        }
        db.createTables();
        auctions = new MariaAuctionDAO(db, LOG, (path, def) -> def);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private long insert(String type, double price, long expire) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO shauctions_auctions (seller_uuid, seller_name, item_serialized, price, "
                 + "list_timestamp, expire_timestamp, type) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, "Vendeur");
            ps.setString(3, "OBJET");
            ps.setDouble(4, price);
            ps.setLong(5, now());
            ps.setLong(6, expire);
            ps.setString(7, type);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private int count(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void sql(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    /** Lance les tâches toutes en même temps, chacune sur son fil. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void oneBuyerWinsAndTheSaleIsWrittenOnce() throws Exception {
        long id = insert("SALE", 1000, now() + 3600);
        List<Callable<String>> buyers = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            String token = UUID.randomUUID().toString();
            buyers.add(() -> auctions.claimForPurchase(id, token, "poc" + token.charAt(0)) != null ? token : null);
        }
        List<String> winners = race(buyers).stream().filter(t -> t != null).toList();
        assertEquals(1, winners.size(), "une seule réservation");
        assertNull(auctions.getAuctionById(id), "annonce réservée invisible");
        MariaAuctionDAO.Sale sale = new MariaAuctionDAO.Sale(id, UUID.randomUUID(), "Vendeur", UUID.randomUUID(), "Acheteur", "Objet", "OBJET",
            1000, "SALE", false);
        assertFalse(auctions.finalizePurchase(id, UUID.randomUUID().toString(), sale), "un autre jeton ne finalise rien");
        assertTrue(auctions.finalizePurchase(id, winners.get(0), sale));
        assertFalse(auctions.finalizePurchase(id, winners.get(0), sale), "pas deux fois");
        assertEquals(1, count("SELECT COUNT(*) FROM shauctions_sold_items WHERE original_auction_id = " + id));
        assertTrue(auctions.isGone(id));
    }

    @Test
    void releasedReservationIsBuyableAgain() throws Exception {
        long id = insert("SALE", 1000, now() + 3600);
        String token = UUID.randomUUID().toString();
        assertNotNull(auctions.claimForPurchase(id, token, "poc1"));
        assertTrue(auctions.isClaimedBy(id, token));
        assertTrue(auctions.releaseClaim(id, token));
        assertNotNull(auctions.getAuctionById(id));
        assertNotNull(auctions.claimForPurchase(id, UUID.randomUUID().toString(), "poc2"));
    }

    /**
     * Chaque mise acceptée est soit la meilleure à la fin, soit remplacée une fois et une seule (remboursée une fois).
     * C'était le double remboursement / la mise perdue du code SQLite entre deux serveurs.
     */
    @Test
    void concurrentBidsRefundEachReplacedBidExactlyOnce() throws Exception {
        long id = insert("BID", 100, now() + 3600);
        List<Callable<Object[]>> bids = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            UUID bidder = UUID.randomUUID();
            double amount = 100 + (i * 37 % 24) * 10;
            bids.add(() -> new Object[]{bidder, amount, auctions.placeBid(id, bidder, "J" + bidder.toString().substring(0, 4), amount, 1, 0, 0,
                UUID.randomUUID().toString(), "poc")});
        }
        List<Object[]> results = race(bids);
        Map<UUID, Double> accepted = new HashMap<>();
        Map<UUID, Integer> replaced = new HashMap<>();
        for (Object[] r : results) {
            BidOutcome out = (BidOutcome) r[2];
            if (out.status() == BidStatus.OK) {
                accepted.put((UUID) r[0], (Double) r[1]);
                if (out.previousBidder() != null) {
                    replaced.merge(out.previousBidder(), 1, Integer::sum);
                    assertEquals(accepted.getOrDefault(out.previousBidder(), out.previousBid()), out.previousBid(), 0.001);
                }
            }
        }
        assertFalse(accepted.isEmpty());
        Object[] current;
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT highest_bidder_uuid, highest_bid FROM shauctions_auctions WHERE id = " + id)) {
            rs.next();
            current = new Object[]{UUID.fromString(rs.getString(1)), rs.getDouble(2)};
        }
        for (Map.Entry<UUID, Double> e : accepted.entrySet()) {
            int times = replaced.getOrDefault(e.getKey(), 0);
            if (e.getKey().equals(current[0])) {
                assertEquals(0, times, "la meilleure mise n'est pas remboursée");
                assertEquals(e.getValue(), (Double) current[1], 0.001);
            } else {
                assertEquals(1, times, "mise remplacée remboursée une fois");
            }
        }
        assertEquals(accepted.size(), count("SELECT COUNT(*) FROM shauctions_bids WHERE auction_id = " + id), "une ligne de journal par mise");
        assertEquals(accepted.size() - 1, replaced.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void bidRulesAreCheckedOnTheLockedRow() throws Exception {
        long id = insert("BID", 100, now() + 3600);
        UUID a = UUID.randomUUID();
        assertEquals(BidStatus.TOO_LOW, auctions.placeBid(id, a, "A", 99, 10, 0, 0, UUID.randomUUID().toString(), "poc1").status());
        String token = UUID.randomUUID().toString();
        assertEquals(BidStatus.OK, auctions.placeBid(id, a, "A", 100, 10, 0, 0, token, "poc1").status());
        assertTrue(auctions.bidRecorded(token));
        assertFalse(auctions.bidRecorded(UUID.randomUUID().toString()));
        assertEquals(BidStatus.ALREADY_HIGHEST, auctions.placeBid(id, a, "A", 500, 10, 0, 0, UUID.randomUUID().toString(), "poc1").status());
        BidOutcome low = auctions.placeBid(id, UUID.randomUUID(), "B", 105, 10, 0, 0, UUID.randomUUID().toString(), "poc2");
        assertEquals(BidStatus.TOO_LOW, low.status());
        assertEquals(110, low.minRequired(), 0.001);
        long sale = insert("SALE", 100, now() + 3600);
        assertEquals(BidStatus.GONE, auctions.placeBid(sale, a, "A", 1000, 10, 0, 0, UUID.randomUUID().toString(), "poc1").status());
    }

    /** Annulation pendant des mises : l'enchérisseur remboursé à l'annulation est celui de la ligne supprimée. */
    @Test
    void cancelDuringBidsRefundsTheLastBidderOnly() throws Exception {
        long id = insert("BID", 100, now() + 3600);
        List<Callable<Object[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID bidder = UUID.randomUUID();
            double amount = 100 + i * 50;
            tasks.add(() -> new Object[]{bidder, amount, auctions.placeBid(id, bidder, "J", amount, 1, 0, 0, UUID.randomUUID().toString(), "poc")});
        }
        tasks.add(() -> new Object[]{null, 0d, auctions.cancel(id, new MariaAuctionDAO.ExpiredEntry(now(), null))});
        List<Object[]> results = race(tasks);
        AuctionItem removed = null;
        Map<UUID, Integer> refunds = new HashMap<>();
        List<UUID> accepted = new ArrayList<>();
        for (Object[] r : results) {
            if (r[0] == null) {
                removed = (AuctionItem) r[2];
                continue;
            }
            BidOutcome out = (BidOutcome) r[2];
            if (out.status() == BidStatus.OK) {
                accepted.add((UUID) r[0]);
                if (out.previousBidder() != null) {
                    refunds.merge(out.previousBidder(), 1, Integer::sum);
                }
            } else {
                // refusée : plus basse qu'une mise déjà prise, ou arrivée après l'annulation
                assertTrue(out.status() == BidStatus.GONE || out.status() == BidStatus.TOO_LOW, out.status().toString());
            }
        }
        assertNotNull(removed, "l'annulation passe");
        if (removed.hasBid()) {
            refunds.merge(removed.getHighestBidderUuid(), 1, Integer::sum);
        }
        for (UUID bidder : accepted) {
            assertEquals(1, refunds.getOrDefault(bidder, 0), "chaque mise prise est rendue une fois");
        }
        assertEquals(accepted.size(), refunds.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, count("SELECT COUNT(*) FROM shauctions_expired_items WHERE original_auction_id = " + id));
        assertTrue(auctions.isGone(id));
    }

    @Test
    void settlementRunsOnceAndWaitsForAntiSnipe() throws Exception {
        long id = insert("BID", 100, now() + 2);
        UUID winner = UUID.randomUUID();
        BidOutcome bid = auctions.placeBid(id, winner, "Gagnant", 150, 1, 30, 30, UUID.randomUUID().toString(), "poc1");
        assertTrue(bid.extended(), "mise de dernière seconde : échéance repoussée");
        assertNull(auctions.settle(id, u -> false, u -> false, a -> "Objet"), "pas encore échue");
        sql("UPDATE shauctions_auctions SET expire_timestamp = " + (now() - 1) + " WHERE id = " + id);
        List<Callable<Settlement>> leaders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            leaders.add(() -> auctions.settle(id, u -> false, u -> false, a -> "Objet"));
        }
        List<Settlement> done = race(leaders).stream().filter(s -> s != null).toList();
        assertEquals(1, done.size(), "réglée une seule fois");
        assertEquals(SettleKind.AWARDED, done.get(0).kind());
        assertEquals(winner, done.get(0).auction().getHighestBidderUuid());
        assertTrue(done.get(0).winnerToExpired());
        assertEquals(1, count("SELECT COUNT(*) FROM shauctions_sold_items WHERE original_auction_id = " + id));
        assertEquals(1, count("SELECT COUNT(*) FROM shauctions_expired_items WHERE original_auction_id = " + id
            + " AND owner_uuid = '" + winner + "'"));
    }

    @Test
    void reservedAuctionsAreNeverSettledNorCancelled() throws Exception {
        long id = insert("SALE", 100, now() + 3600);
        String token = UUID.randomUUID().toString();
        assertNotNull(auctions.claimForPurchase(id, token, "poc1"));
        assertNull(auctions.cancel(id, null));
        sql("UPDATE shauctions_auctions SET expire_timestamp = " + (now() - 1) + " WHERE id = " + id);
        assertNull(auctions.settle(id, u -> false, u -> false, a -> "Objet"));
        assertTrue(auctions.getExpiredAuctions().isEmpty(), "une annonce réservée n'apparaît pas comme échue");
    }

    @Test
    void expiredItemIsClaimedOnce() throws Exception {
        sql("INSERT INTO shauctions_expired_items (original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp) "
            + "VALUES (42, '" + UUID.randomUUID() + "', 'J', 'OBJET', " + now() + ")");
        long expiredId = count("SELECT id FROM shauctions_expired_items WHERE original_auction_id = 42");
        MariaExpiredItemDAO expired = new MariaExpiredItemDAO(db, LOG, (path, def) -> def);
        Callable<Boolean> claim = () -> expired.markAsClaimed(expiredId);
        List<Callable<Boolean>> claims = new ArrayList<>(Collections.nCopies(12, claim));
        assertEquals(1, race(claims).stream().filter(Boolean::booleanValue).count());
        assertEquals(1, expired.clearExpiredItems(0, true));
        sql("INSERT INTO shauctions_expired_items (original_auction_id, owner_uuid, owner_name, item_serialized, expiration_timestamp) "
            + "VALUES (43, '" + UUID.randomUUID() + "', 'J', 'OBJET', 10)");
        assertEquals(1, expired.clearExpiredItems(now(), false), "purge des vieux invendus (requête corrélée)");
    }

    @Test
    void interruptedListingIsReportedAndDeleted() throws Exception {
        long id = insert("SALE", 100, now() + 3600);
        sql("UPDATE shauctions_auctions SET claim_token = '" + UUID.randomUUID() + "', claim_kind = 'LIST', claimed_by = 'poc1', "
            + "claimed_at = " + (now() - 3600) + " WHERE id = " + id);
        List<MariaAuctionDAO.StaleClaim> stale = auctions.staleClaims(now() - 600);
        assertEquals(1, stale.size());
        assertEquals("LIST", stale.get(0).kind());
        assertTrue(auctions.deleteStaleListing(id, now() - 600));
        assertTrue(auctions.isGone(id));
    }

    @Test
    void sqliteIsImportedOnceForTheWholeNetwork() throws Exception {
        File file = Files.createTempFile("shauctions", ".db").toFile();
        file.deleteOnExit();
        try (Connection lite = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()); Statement st = lite.createStatement()) {
            // ancienne base : ni type, ni enchères, ni raison d'annulation, ni récapitulatif
            st.execute("CREATE TABLE auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT, seller_name TEXT, item_serialized TEXT, "
                + "price REAL, list_timestamp INTEGER, expire_timestamp INTEGER)");
            st.execute("CREATE TABLE sold_items (id INTEGER PRIMARY KEY AUTOINCREMENT, original_auction_id INTEGER, seller_uuid TEXT, "
                + "seller_name TEXT, buyer_uuid TEXT, buyer_name TEXT, item_name TEXT, item_serialized TEXT, price REAL, sold_timestamp INTEGER)");
            st.execute("CREATE TABLE expired_items (id INTEGER PRIMARY KEY AUTOINCREMENT, original_auction_id INTEGER UNIQUE, owner_uuid TEXT, "
                + "owner_name TEXT, item_serialized TEXT, expiration_timestamp INTEGER, claimed BOOLEAN DEFAULT 0)");
            String u = UUID.randomUUID().toString();
            st.execute("INSERT INTO auctions VALUES (77, '" + u + "', 'V', 'OBJ', 10.5, 1, " + (now() + 3600) + ")");
            st.execute("INSERT INTO sold_items VALUES (5, 70, '" + u + "', 'V', '" + u + "', 'A', 'Objet', 'OBJ', 3, 1)");
            st.execute("INSERT INTO sold_items VALUES (6, 70, '" + u + "', 'V', '" + u + "', 'A', 'Objet', 'OBJ', 3, 1)");
            st.execute("INSERT INTO expired_items VALUES (9, 71, '" + u + "', 'V', 'OBJ', 1, 1)");
        }
        assertEquals(3, db.importSqliteOnce(file, "poc1"), "le doublon de vente est écarté");
        assertEquals(-1, db.importSqliteOnce(file, "poc2"), "jamais deux fois");
        AuctionItem imported = auctions.getAuctionById(77);
        assertNotNull(imported, "ids gardés");
        assertEquals("SALE", imported.getType());
        assertEquals(10.5, imported.getPrice(), 0.001);
        long next = insert("SALE", 1, now() + 60);
        assertTrue(next > 77, "la numérotation reprend après les ids importés");
    }
}
