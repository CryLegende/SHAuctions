<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHAuctions

*Hôtel des ventes (/hdv) : ventes directes et enchères, invendus, historique et annulation par le staff.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Hôtel des ventes (/hdv) : ventes directes et enchères, invendus, historique et annulation par le staff.

- **Saisie par dialogue natif** : sur un client 1.21.6 ou plus, miser sur une enchère (clic gauche dans la page Enchères) ou donner la raison d'une annulation staff (clic droit) ouvre un dialogue avec un champ de saisie et deux boutons, **Miser / Valider** et **Annuler**, à la place de « saisissez dans le chat, tapez annuler ». Échap annule proprement. Le dialogue rappelle l'objet, la meilleure offre, la mise minimum et le temps restant.
- **Mêmes règles que le chat** : le montant est lu comme au chat (virgule décimale, espaces ignorés, strictement positif) puis transmis à la même méthode de mise, qui revérifie tout côté serveur : enchère encore ouverte, vendeur, meilleur enchérisseur, minimum, incrément, solde, concurrence. Les messages d'erreur sont ceux du chat. Aucun plafond n'est imposé aux gros montants (champ texte, pas de curseur).
- **Saisie à usage unique** : chaque dialogue porte un jeton aléatoire ; un clic n'est pris en compte que si le jeton, le type de saisie et le joueur correspondent, avant expiration (`dialogues.expiration-secondes`). La saisie est annulée si le joueur se déconnecte ou si l'enchère disparaît (annulée, expirée, remportée).
- **Repli sur le chat** : les clients plus anciens (ViaVersion donne leur version), `dialogues.actif: false` ou une API de dialogues absente gardent exactement la saisie par chat d'avant.
- **Saisie au chat fiabilisée** : une mise refusée (trop basse, solde insuffisant, course perdue) ne ferme plus l'attente : le message d'erreur habituel s'affiche, puis le plugin redemande un montant (`bid_retry_prompt`), au lieu de laisser le montant retapé partir dans le chat public. L'attente ne se termine que sur une mise acceptée, `annuler`, l'expiration (60 s depuis la dernière tentative) ou une enchère devenue indisponible. `NaN`, `Infinity`, `0` et les négatifs sont refusés comme un montant invalide.

Les mises en vente, achats, mises, enchères remportées, retraits et annulations du staff sont écrits dans les fichiers `mises-en-vente`, `achats`, `encheres`, `retraits` et `staff` de SHLogs (`plugins/SHLogs/SHAuctions/`) ; les enchères du serveur dans `encheres-serveur`.

Utilisé sur : **Opale**.

## Enchères du serveur

Quelques fois par semaine, le serveur met lui-même un lot aux enchères dans la page Enchères (section `encheres-serveur` de `config.yml`, `actif: true` pour l'activer). But : donner aux SHC une dépense désirable qui les retire de l'économie.

- **Même enchère que celle d'un joueur** : vendeur affiché `vendeur` (SkyHand), mises au dialogue ou au chat, remboursement du surenchéri, anti-snipe. Chaque lot a son prix de départ et son écart minimal entre deux mises (`increment-min`).
- **Argent retiré du jeu** : l'offre gagnante, déjà mise de côté par la mise, n'est versée à personne.
- **Lot remis par commandes** : les `commandes` du lot sont lancées par la console pour le gagnant (`%player%`). Gagnant connecté avec une place libre : tout de suite. Hors ligne ou inventaire plein : le lot part dans ses invendus (signalés à la connexion, `/hdv expired`) et les commandes sont lancées quand il le récupère. L'objet affiché dans l'HDV n'est jamais donné. L'objet porte son lot (id, commandes, incrément) : un reload qui change les lots ne touche pas une enchère en cours, et en mode réseau n'importe quel serveur sait la régler.
- **Sans offre** : l'enchère disparaît, rien ne bouge.
- **Planning** : chaque lundi 00:00 (heure du serveur), `par-semaine` débuts tirés dans la `plage-horaire`, répartis sur les jours, chacun dans une tranche différente de la plage (jamais au même moment de la journée), avec `ecart-minimal-minutes` entre deux débuts ; durée tirée dans `duree-minutes`. La première semaine reçoit sa part au prorata. Le planning est gardé dans `plugins/SHAuctions/encheres-serveur.yml` : les redémarrages ne le changent pas ; une enchère dont l'heure passe serveur éteint est lancée au démarrage si sa plage n'est pas finie, sinon notée manquée. Changer les réglages du planning refait la suite de la semaine.
- **Plafond par joueur** : `max-gagnees-par-joueur` enchères du serveur par semaine (lundi à dimanche), remportées ou déjà en tête : au plafond, une nouvelle mise est refusée (`server_auction_cap_reached`). Compté dans l'historique des ventes, donc valable sur tout le réseau.
- **Annonces** : début (`server_auction_start`, cliquable : ouvre `/hdv encheres`, objet survolable, son `son-annonce` si réglé) et résultat (`server_auction_result`) à tout le serveur ; en mode réseau, aux autres serveurs aussi.
- **Mode réseau** : seul le serveur où `encheres-serveur.actif` vaut `true` tient le planning et lance les enchères ; tous les affichent et prennent les mises, le serveur meneur les règle.

## Mode réseau (plusieurs Opale)

Avec **SHNetwork** chargé et `reseau.actif: true` (par défaut), un seul hôtel des ventes sert tous les serveurs du réseau. Sans SHNetwork, rien ne change : base SQLite locale, mêmes commandes, mêmes messages.

- **Stockage** : MariaDB (`reseau.base-de-donnees`), tables `shauctions_auctions`, `shauctions_sold_items`, `shauctions_expired_items`, `shauctions_wanted`, `shauctions_bids` (journal des mises) et `shauctions_state`. Le premier serveur démarré en mode réseau y reprend une fois sa base SQLite, ids compris ; chaque serveur renomme ensuite la sienne en `shauctions.db.avant-reseau`, pour qu'un retour sans SHNetwork ne fasse jamais réapparaître des annonces déjà vendues. Base injoignable : l'HDV se désactive sur ce serveur au lieu de retomber sur SQLite.
- **Achat** : l'annonce est réservée en base avant tout mouvement d'argent (`UPDATE ... WHERE claim_token IS NULL`) ; un seul serveur l'obtient. Puis l'acheteur paie, le vendeur est payé, et l'annonce est supprimée avec l'écriture de la vente dans une seule transaction. Un échec en route rend l'argent et libère la réservation.
- **Mise** : l'argent est mis de côté, puis une transaction verrouille l'enchère (`SELECT ... FOR UPDATE`), revérifie toutes les règles (échéance, vendeur, meilleur enchérisseur, minimum, anti-snipe) et rend l'enchérisseur qu'elle remplace : c'est lui qui est remboursé, une fois. Chaque mise prise laisse une ligne dans `shauctions_bids`.
- **Annulation et échéance** : transaction qui verrouille, supprime et range l'objet ; le remboursement et le paiement du vendeur se calculent sur la ligne verrouillée. Les échéances et le nettoyage des invendus ne tournent que sur le serveur meneur (`leader` SHNetwork, tâche `shauctions-echeances`).
- **Mise en vente** : l'annonce est écrite réservée (invisible) et ne devient visible qu'une fois l'objet retiré de l'inventaire.
- **Joueur sur un autre serveur** : surenchère, vente, enchère remportée, remboursement et annulation staff lui sont annoncés par le bus (`reseau.canal`). Les annonces d'enchères et le rafraîchissement des menus ouverts passent par le même canal. Un objet gagné par un joueur connecté sur un autre serveur que le meneur va dans ses invendus (`/hdv expired`).
- **Opération interrompue** (serveur arrêté brutalement au milieu) : une mise en vente jamais publiée est supprimée après 10 minutes (l'inventaire du vendeur est revenu à sa dernière sauvegarde, l'objet y est) ; un achat interrompu est signalé une fois dans la console du meneur pour une vérification à la main.

## Dépendances

- Optionnelles : `Vault`, `ViaVersion` (version du client pour choisir dialogue ou chat ; sans lui tous les clients sont considérés compatibles), `SHLogs` (journal des actions, sinon écrit dans la console), `SHNetwork` (mode réseau)
- Bibliothèques chargées par Paper (`libraries:`) : HikariCP et le pilote MariaDB, utilisés seulement en mode réseau

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/hdv` | `/ah`, `/auctionhouse` | Ouvre l'hôtel des ventes. Sous-commandes joueur : `sell`, `bid`, `encheres` (page Enchères), `history`, `expired`, `ownsales`, `recherche`. | `shauctions.use` |
| `/shauctions reload` | — | Recharge `config.yml`, `messages.yml` et les menus. Un fichier illisible ne change rien : le message donne le fichier et l'erreur. Replanifie la tâche des échéances. Les sections `reseau`, `database` et `economy.use_vault` demandent un redémarrage ; les données ne sont jamais touchées. | `shauctions.admin.reload` |
| `/shauctions info <joueur>` | — | Ventes, enchères, invendus et ventes terminées d'un joueur, avec ses limites s'il est connecté. | `shauctions.admin.info` |
| `/shauctions plan` | — | Planning de la semaine des enchères du serveur : date, durée, état (prévue, lancée et lot, retirée, manquée). | `shauctions.admin.plan` |
| `/shauctions launch <lot\|random> [durée]` | — | Lance tout de suite une enchère du serveur sur ce lot, hors planning (durée : `2m`, `1h30m`, `3` = 3 h ; sans durée, tirée dans `duree-minutes`). | `shauctions.admin.launch` |
| `/shauctions skip` | — | Retire du planning la prochaine enchère du serveur prévue. | `shauctions.admin.skip` |

`/shauctions` marche aussi depuis la console. `/hdv reload` reste accepté et fait la même chose que `/shauctions reload`. Sans argument, `/shauctions` rappelle les sous-commandes que l'on a le droit d'utiliser.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shauctions.use` | Permet d'utiliser les fonctionnalités de base de l'HDV (ouvrir, voir). | `true` |
| `shauctions.sell` | Permet de mettre des items en vente. | `true` |
| `shauctions.buy` | Permet d'acheter des items. | `true` |
| `shauctions.bid` | Permet de mettre des items aux enchères et de miser. | `true` |
| `shauctions.menu.bids` | Permet d'ouvrir la page des Enchères. | `true` |
| `shauctions.menu.ownsales` | Affiche le bouton "Vos objets en vente" dans les menus. | `true` |
| `shauctions.menu.expired` | Affiche le bouton "Vos invendus" dans les menus. | `true` |
| `shauctions.menu.history` | Affiche le bouton "Historique des ventes" dans les menus. | `true` |
| `shauctions.history.self` | Permet de voir son propre historique de ventes. | `true` |
| `shauctions.history.global` | Permet de voir l'historique global des ventes. | `op` |
| `shauctions.expired.claim` | Permet de réclamer ses items expirés. | `true` |
| `shauctions.cancel.self` | Permet d'annuler ses propres ventes. | `true` |
| `shauctions.cancel.others` | Permet d'annuler les ventes des autres joueurs (admin). | `op` |
| `shauctions.ownsales.view` | Permet d'utiliser la commande `/hdv ownsales` (le bouton des menus passe par `shauctions.menu.ownsales`). | `op` |
| `shauctions.recherches.use` | Onglet Recherches de l'HDV. | `true` |
| `shauctions.recherches.admin` | Supprimer n'importe quelle recherche, sans délai entre deux publications. | `op` |
| `shauctions.admin` | Toute l'administration (parent des permissions suivantes). | `op` |
| `shauctions.admin.reload` | `/shauctions reload` et `/hdv reload`. | `op` |
| `shauctions.admin.info` | `/shauctions info`. | `op` |
| `shauctions.admin.plan` | `/shauctions plan`. | `op` |
| `shauctions.admin.launch` | `/shauctions launch`. | `op` |
| `shauctions.admin.skip` | `/shauctions skip`. | `op` |

Paliers (nœuds construits à partir d'un nombre, non déclarés) :

| Permission | Effet |
|---|---|
| `shauctions.limit.<n>` (ou `unlimited`, `*`) | Ventes simultanées, exemple `shauctions.limit.4`. La plus grande valeur l'emporte. |
| `shauctions.bidlimit.<n>` (ou `unlimited`, `*`) | Enchères simultanées, exemple `shauctions.bidlimit.1`. |
| `shauctions.recherches.max.<n>` | Recherches en cours, exemple `shauctions.recherches.max.6`. |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHAuctions/
├── <a href="src/main/resources/config.yml">config.yml</a>        section encheres-serveur (planning, plafond, lots), dialogues.actif, dialogues.min-protocol (771 = 1.21.6), dialogues.expiration-secondes, section logs (fichier et format de chaque ligne SHLogs), section reseau (mode réseau avec SHNetwork)
├── encheres-serveur.yml  planning de la semaine des enchères du serveur (écrit par le plugin)
├── <a href="src/main/resources/messages.yml">messages.yml</a>      messages server_* (enchères du serveur), section dialogues (titres, champs, boutons, erreurs), messages dialog_* et bid_retry_prompt ; les clés manquantes (dont generic_error_occurred) sont ajoutées au fichier du serveur au premier démarrage
└── menus/
    ├── <a href="src/main/resources/menus/auction_bids.yml">auction_bids.yml</a>
    ├── <a href="src/main/resources/menus/confirm_purchase.yml">confirm_purchase.yml</a>
    ├── <a href="src/main/resources/menus/expired_items.yml">expired_items.yml</a>
    ├── <a href="src/main/resources/menus/main_auction_house.yml">main_auction_house.yml</a>
    ├── <a href="src/main/resources/menus/own_sales.yml">own_sales.yml</a>
    ├── <a href="src/main/resources/menus/own_wanted_requests.yml">own_wanted_requests.yml</a>
    ├── <a href="src/main/resources/menus/sales_history.yml">sales_history.yml</a>
    └── <a href="src/main/resources/menus/wanted_requests.yml">wanted_requests.yml</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`. Les tests du planning des enchères du serveur (nombre par semaine, plage horaire, écart minimal, répartition sur les jours et les moments de la journée, planning relu après redémarrage, plafond par joueur) tournent sans serveur. Les tests du mode réseau (une seule réservation, chaque mise remplacée remboursée une fois, échéance réglée une fois, importation) tournent sur une vraie base MariaDB jetable : `SHAU_TEST_URL`, `SHAU_TEST_USER`, `SHAU_TEST_PASSWORD` ; sans elles ils sont ignorés.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
