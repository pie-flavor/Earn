package flavor.pie.earn;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import com.google.inject.Inject;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.key.Key;
import org.spongepowered.api.data.key.KeyFactory;
import org.spongepowered.api.data.value.mutable.MapValue;
import org.spongepowered.api.data.value.mutable.Value;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.entity.DisplaceEntityEvent;
import org.spongepowered.api.event.filter.Getter;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStartedServerEvent;
import org.spongepowered.api.event.item.inventory.ClickInventoryEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.economy.Currency;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.service.permission.Subject;
import org.spongepowered.api.service.permission.SubjectCollection;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.TextTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Plugin(id = "earn", name = "Earn", version = "1.0", authors = "pie_flavor", description = "Earn money by being online.")
public class Earn {
    public static Key<Value<LocalDate>> EARNINGS_DATE;
    public static Key<MapValue<Currency, BigDecimal>> AMOUNT_EARNED;
    @Inject
    Game game;
    @Inject
    PluginContainer plugin;
    @Inject @DefaultConfig(sharedRoot = true)
    ConfigurationLoader<CommentedConfigurationNode> loader;
    @Inject @DefaultConfig(sharedRoot = true)
    Path configFile;
    @Inject
    Logger logger;
    ConfigurationNode root;
    EarningsData.Builder builder;
    LoadingCache<UUID, Group> cache = CacheBuilder.newBuilder().build(new CacheLoader<UUID, Group>() {
        @Override
        public Group load(UUID id) throws ObjectMappingException {
            UserStorageService usvc = game.getServiceManager().provideUnchecked(UserStorageService.class);
            User u = usvc.get(id).get();
            if (u.isOnline()) u = u.getPlayer().get();
            PermissionService svc = game.getServiceManager().provideUnchecked(PermissionService.class);
            SubjectCollection subjects = svc.getGroupSubjects();
            EarningsData data = u.getOrCreate(EarningsData.class).get();
            ConfigurationNode node = null;
            Map<Object, ? extends ConfigurationNode> map = root.getNode("groups").getChildrenMap();
            for (Object o : map.keySet()) {
                String s = o.toString();
                Subject subject = subjects.get(s);
                if (u.isChildOf(subject)) {
                    node = map.get(o);
                    break;
                }
            }
            Group group = new Group();
            group.maxAmounts = ImmutableMap.copyOf(EarningsData.convertBackward(node.getNode("max-per-day").getValue(new TypeToken<Map<String, Double>>(){})));
            group.earnRates = ImmutableMap.copyOf(EarningsData.convertBackward(node.getNode("payout").getValue(new TypeToken<Map<String, Double>>(){})));
            group.timeout = node.getNode("afk-timeout").getInt();
            return group;
        }
    });
    Map<UUID, Instant> lastAction = Maps.newHashMap();

    @Listener
    public void preInit(GamePreInitializationEvent e) throws IOException {
        if (!Files.exists(configFile)) {
            try {
                game.getAssetManager().getAsset(this, "default.conf").get().copyToFile(configFile);
            } catch (IOException ex) {
                logger.error("Could not copy default config! Disabling plugin.");
                disable();
                throw ex;
            }
        }
        try {
            root = loader.load();
        } catch (IOException ex) {
            logger.error("Could not load config! Disabling plugin.");
            disable();
            throw ex;
        }
        EARNINGS_DATE = KeyFactory.makeSingleKey(LocalDate.class, Value.class, DataQuery.of("earningsDate"));
        AMOUNT_EARNED = KeyFactory.makeMapKey(Currency.class, BigDecimal.class, DataQuery.of("amountsEarned"));
        builder = new EarningsData.Builder();
        game.getDataManager().register(EarningsData.class, EarningsData.Immutable.class, builder);
    }

    @Listener
    public void init(GameInitializationEvent e) {
        CommandSpec stats = CommandSpec.builder()
                .executor(this::progress)
                .description(Text.of("Shows you your daily earnings."))
                .arguments(
                        GenericArguments.playerOrSource(Text.of("player")),
                        GenericArguments.catalogedElement(Text.of("currency"), Currency.class)
                )
                .build();
        CommandSpec earn = CommandSpec.builder()
                .child(stats, "stats")
                .build();
        game.getCommandManager().register(this, earn, "earn");
    }

    public void reload(GameReloadEvent e) throws IOException {
        try {
            root = loader.load();
            cache.invalidateAll();
        } catch (IOException ex) {
            logger.error("Error reloading config!");
            throw ex;
        }
    }

    void disable() {
        game.getEventManager().unregisterPluginListeners(this);
        game.getCommandManager().getCommands().forEach(game.getCommandManager()::removeMapping);
    }

    CommandResult progress(CommandSource src, CommandContext args) throws CommandException {
        Player p = args.<Player>getOne("player").get();
        if (p != src) {
            args.checkPermission(src, "earn.progress.other");
        }
        Optional<Currency> currency_ = args.getOne("currency");
        Group group = cache.getUnchecked(p.getUniqueId());
        EarningsData data = p.getOrCreate(EarningsData.class).get();
        String prefix = src == p ? "You have" : p.getName() + " has";
        if (!currency_.isPresent()) {
            TextTemplate template = TextTemplate.of(prefix, " earned ", TextTemplate.arg("earned").build(), " out of a possible ", TextTemplate.arg("possible").build(), " today.");
            TextTemplate template2 = TextTemplate.of(prefix, " earned ", TextTemplate.arg("earned").build(), " today.");
            BigDecimal total = BigDecimal.ZERO;
            int success = 0;
            for (Map.Entry<Currency, BigDecimal> entry : data.amounts.entrySet()) {
                BigDecimal possible = group.maxAmounts.getOrDefault(entry.getKey(), BigDecimal.ONE.negate());
                BigDecimal earned = entry.getValue();
                if (possible.compareTo(BigDecimal.ONE.negate()) == 0)
                    p.sendMessage(template2.apply(ImmutableMap.of("earned", entry.getKey().format(earned))).build());
                else
                    p.sendMessage(template.apply(ImmutableMap.of("earned", entry.getKey().format(earned), "possible", possible)).build());
                total = total.add(earned);
                success++;
            }
            int amount;
            try {
                amount = total.intValueExact();
            } catch (ArithmeticException e) {
                amount = Integer.MAX_VALUE;
            }
            return CommandResult.builder().successCount(success).queryResult(amount).build();
        } else {
            Currency currency = currency_.get();
            BigDecimal earned = data.amounts.getOrDefault(currency, BigDecimal.ZERO);
            if (earned.compareTo(BigDecimal.ZERO) == 0) {
                src.sendMessage(Text.of(prefix, " not earned any ", currency.getPluralDisplayName(), " today."));
            } else {
                BigDecimal possible = group.maxAmounts.getOrDefault(currency, BigDecimal.ONE.negate());
                if (possible.compareTo(BigDecimal.ONE.negate()) == 0) {
                    src.sendMessage(Text.of(prefix, " earned ", currency.format(earned), " today."));
                } else {
                    src.sendMessage(Text.of(prefix, " earned ", currency.format(earned), " out of a possible ", currency.format(possible), " today."));
                }
            }
            int amount;
            try {
                amount = earned.intValueExact();
            } catch (ArithmeticException ex) {
                amount = Integer.MAX_VALUE;
            }
            return CommandResult.builder().successCount(1).queryResult(amount).build();
        }
    }

    void tick() {
        for (Player p : game.getServer().getOnlinePlayers()) {
            Group group = cache.getUnchecked(p.getUniqueId());
            if (lastAction.getOrDefault(p.getUniqueId(), Instant.now()).until(Instant.now(), ChronoUnit.MINUTES) > group.timeout) continue;
            EarningsData data = p.getOrCreate(EarningsData.class).get();
            LocalDate today = LocalDate.now();
            if (data.date.isBefore(today)) {
                data.date = today;
                data.amounts = Maps.newHashMap();
            }
            for (Currency currency : group.earnRates.keySet()) {
                BigDecimal earned = data.amounts.getOrDefault(currency, BigDecimal.ZERO);
                BigDecimal possible = group.maxAmounts.getOrDefault(currency, BigDecimal.ZERO);
                if (possible.compareTo(earned) == 0) continue;
                BigDecimal total = earned.add(group.earnRates.get(currency));
                if (total.compareTo(possible) >= 0) {
                    total = possible;
                    p.sendMessage(Text.of("You have reached the maximum earnings of "+currency.getPluralDisplayName()+" for the day."));
                }
                data.amounts.put(currency, total);
            }
        }
    }

    @Listener
    public void started(GameStartedServerEvent e) {
        Task.builder().delay(1, TimeUnit.MINUTES).name("earn-S-EarnMoney").execute(this::tick).submit(this);
    }

    @Listener
    public void displace(DisplaceEntityEvent.Move e, @Getter("getTargetEntity") Player p) {
        lastAction.put(p.getUniqueId(), Instant.now());
    }

    @Listener
    public void chat(MessageChannelEvent.Chat e, @First Player p) {
        lastAction.put(p.getUniqueId(), Instant.now());
    }

    @Listener
    public void click(ClickInventoryEvent e, @First Player p) {
        lastAction.put(p.getUniqueId(), Instant.now());
    }

    class Group {
        int timeout;
        Map<Currency, BigDecimal> maxAmounts;
        Map<Currency, BigDecimal> earnRates;
    }
}
