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

Les mises en vente, achats, enchères remportées, retraits et annulations du staff sont écrits dans les fichiers `mises-en-vente`, `achats`, `encheres`, `retraits` et `staff` de SHLogs (`plugins/SHLogs/SHAuctions/`).

Utilisé sur : **Opale**.

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
| `/hdv` | `/ah`, `/auctionhouse` | Ouvre l'hôtel des ventes ou gère les enchères. | — |

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
| `shauctions.reload` | Permet de recharger la configuration du plugin. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHAuctions/
├── <a href="src/main/resources/config.yml">config.yml</a>        dialogues.actif, dialogues.min-protocol (771 = 1.21.6), dialogues.expiration-secondes, section logs (fichier et format de chaque ligne SHLogs), section reseau (mode réseau avec SHNetwork)
├── <a href="src/main/resources/messages.yml">messages.yml</a>      section dialogues (titres, champs, boutons, erreurs), messages dialog_* et bid_retry_prompt ; les clés manquantes (dont generic_error_occurred) sont ajoutées au fichier du serveur au premier démarrage
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

Le jar est généré dans `target/`. Les tests du mode réseau (une seule réservation, chaque mise remplacée remboursée une fois, échéance réglée une fois, importation) tournent sur une vraie base MariaDB jetable : `SHAU_TEST_URL`, `SHAU_TEST_USER`, `SHAU_TEST_PASSWORD` ; sans elles ils sont ignorés.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
