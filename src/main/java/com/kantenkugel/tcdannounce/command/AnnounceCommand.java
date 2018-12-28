package com.kantenkugel.tcdannounce.command;

import com.kantenkugel.common.FixedSizeCache;
import com.kantenkugel.tcdannounce.GuildSettings;
import com.kantenkugel.tcdannounce.Utils;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageUpdateEvent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kantenkugel.tcdannounce.Utils.getAnnouncementMessage;
import static com.kantenkugel.tcdannounce.Utils.getRolesByName;

public class AnnounceCommand implements ICommand {
    private static final String[] NAMES = {"announce"};
    private static final Map<Long, Message> CACHE = new FixedSizeCache<>(5);

    @Override
    public void handleCommand(GuildMessageReceivedEvent event, GuildSettings.GuildSetting settings, String args) {
        //check if member is allowed to announce based on roles
        if(!settings.isAnnouncer(event.getMember()))
            return;

        //can bot talk in current channel? (important for feedback)
        if(!event.getChannel().canTalk()) {
            event.getAuthor().openPrivateChannel().queue(ch -> {
                ch.sendMessage("Can not send messages in channel "+event.getChannel().getName()).queue();
            }, err -> {});
            return;
        }

        //check if bot can manage roles
        if(!event.getGuild().getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            event.getChannel().sendMessage("Missing MANAGE_ROLES permission!").queue();
            return;
        }

        //get and check argument list
        String[] splits = args.isEmpty() ? null : args.split("\\s*\\|\\s*", 3);
        if(splits == null || splits.length < 2) {
            event.getChannel().sendMessage("Syntax: `announce role_name [ | channel_mention] | text`").queue();
            return;
        }

        //get text to send and channel to send in
        TextChannel channel;
        String textToSend;
        if(splits.length == 3) {
            if(event.getMessage().getMentionedChannels().size() == 0) {
                event.getChannel().sendMessage("Channel mention missing!").queue();
                return;
            }
            channel = event.getMessage().getMentionedChannels().get(0);
            textToSend = splits[2].trim();
        } else {
            channel = event.getChannel();
            textToSend = splits[1].trim();
        }

        //can bot talk in channel?
        if(!channel.canTalk()) {
            event.getChannel().sendMessage("Can not talk in target channel").queue();
            return;
        }

        //get and check role to mention
        List<Role> roles = getRolesByName(event.getGuild(), splits[0]).stream()
                .filter(settings::isAnnouncementRole)
                .collect(Collectors.toList());
        if(roles.size() == 0) {
            event.getChannel().sendMessage("No (announcement) roles matching "+splits[0].trim()+" found!").queue();
        } else if(roles.size() > 1) {
            event.getChannel().sendMessage("Too many announcement roles with this name!").queue();
        } else if(!event.getGuild().getSelfMember().canInteract(roles.get(0))) {
            event.getChannel().sendMessage("Can't interact with this role!").queue();
        } else {
            Role role = roles.get(0);
            //announce
            if(role.isMentionable()) {
                channel.sendMessage(getAnnouncementMessage(role, textToSend, event.getMember()))
                        .queue(msg -> {
                            //cache sent message for future edits
                            CACHE.put(event.getMessageIdLong(), msg);
                            if(event.getChannel() != channel)
                                event.getChannel().sendMessage("Successfully announced").queue();
                        });
            } else {
                role.getManager().setMentionable(true)
                        .queue(v -> {
                            channel.sendMessage(getAnnouncementMessage(role, textToSend, event.getMember()))
                                    .queue(msg -> {
                                        //cache sent message for future edits
                                        CACHE.put(event.getMessageIdLong(), msg);
                                        role.getManager().setMentionable(false).queue();
                                        if(event.getChannel() != channel)
                                            event.getChannel().sendMessage("Successfully announced").queue();
                                    }, err -> {
                                        role.getManager().setMentionable(false).queue();
                                    });
                        });
            }
        }
    }

    @Override
    public void handleUpdate(GuildMessageUpdateEvent event) {
        //handle updates of announcement commands
        Message botMsg = CACHE.get(event.getMessageIdLong());
        if(botMsg == null)
            return;
        String[] splits = event.getMessage().getContentRaw().split("\\s*\\|\\s*", 3);
        botMsg.editMessage(getAnnouncementMessage(botMsg.getMentionedRoles().get(0), splits[splits.length-1].trim(), event.getMember())).queue();
    }

    @Override
    public String[] getNames() {
        return NAMES;
    }
}