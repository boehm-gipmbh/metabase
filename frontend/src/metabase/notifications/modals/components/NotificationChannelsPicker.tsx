import { t } from "ttag";

import { useListChannelsQuery } from "metabase/api";
import { useSelector } from "metabase/lib/redux";
import { isNotNull } from "metabase/lib/types";
import {
  type ChannelToAddOption,
  NotificationChannelsAddMenu,
} from "metabase/notifications/modals/components/NotificationChannelsAddMenu";
import { getUser } from "metabase/selectors/user";
import { Stack } from "metabase/ui";
import type {
  ChannelApiResponse,
  NotificationHandler,
  NotificationHandlerEmail,
  NotificationHandlerHttp,
  NotificationHandlerSlack,
  User,
} from "metabase-types/api";

import { EmailChannelEdit } from "../../EmailChannelEdit";
import { SlackChannelEdit } from "../../SlackChannelEdit";
import { WebhookChannelEdit } from "../../WebhookChannelEdit";

const DEFAULT_CHANNELS_CONFIG = {
  email: { name: t`Email`, type: "email" },
  slack: { name: t`Slack`, type: "slack" },
  http: { name: t`Http`, type: "http" },
};

interface NotificationChannelsPickerProps {
  notificationHandlers: NotificationHandler[];
  channels: ChannelApiResponse["channels"] | undefined;
  users: User[];
  onChange: (newHandlers: NotificationHandler[]) => void;
  emailRecipientText: string;
  getInvalidRecipientText: (domains: string) => string;
  isAdminUser: boolean;
}

export const NotificationChannelsPicker = ({
  notificationHandlers,
  channels: nullableChannels,
  users,
  onChange,
  getInvalidRecipientText,
  isAdminUser,
}: NotificationChannelsPickerProps) => {
  const { data: notificationChannels = [] } = useListChannelsQuery();
  const user = useSelector(getUser);

  const addChannel = (channel: ChannelToAddOption) => {
    const newChannel: NotificationHandler = {
      channel_type: channel.type,
      channel_id: channel.channel_id,
      recipients:
        channel.type === "channel/email" && user
          ? [
              {
                type: "notification-recipient/user",
                user_id: user.id,
              },
            ]
          : [],
    };

    onChange(notificationHandlers.concat(newChannel));
  };

  const onChannelChange = (
    oldConfig: NotificationHandler,
    newConfig: NotificationHandler,
  ) => {
    const updatedChannels = notificationHandlers.map(value =>
      value === oldConfig ? newConfig : value,
    );

    onChange(updatedChannels);
  };

  const onRemoveChannel = (channel: NotificationHandler) => {
    const updatedChannels = notificationHandlers.filter(
      value => value !== channel,
    );

    onChange(updatedChannels);
  };

  // Default to show the default channels until full formInput is loaded
  const channels = (nullableChannels ||
    DEFAULT_CHANNELS_CONFIG) as ChannelApiResponse["channels"];

  const emailChannel = notificationHandlers.find(
    ({ channel_type }) => channel_type === "channel/email",
  ) as NotificationHandlerEmail | undefined;
  const slackChannel = notificationHandlers.find(
    ({ channel_type }) => channel_type === "channel/slack",
  ) as NotificationHandlerSlack | undefined;
  const hookChannels = notificationHandlers.filter(
    ({ channel_type }) => channel_type === "channel/http",
  ) as NotificationHandlerHttp[];

  const channelsToAdd: ChannelToAddOption[] = [
    channels.email.configured && !emailChannel
      ? {
          type: "channel/email" as const,
          name: t`Email`,
        }
      : null,
    channels.slack.configured && !slackChannel
      ? {
          type: "channel/slack" as const,
          name: t`Slack`,
        }
      : null,
    ...(channels.http.configured
      ? notificationChannels
          .filter(
            ({ id }) =>
              !hookChannels.find(({ channel_id }) => id === channel_id),
          )
          .map(({ id, type, name }) => ({
            channel_id: id,
            type,
            name,
          }))
      : []),
  ].filter(isNotNull);

  return (
    <Stack spacing="xl" align="start">
      {channels.email.configured && !!emailChannel && (
        <EmailChannelEdit
          channel={emailChannel}
          users={users}
          invalidRecipientText={getInvalidRecipientText}
          onChange={newConfig => onChannelChange(emailChannel, newConfig)}
          onRemoveChannel={() => onRemoveChannel(emailChannel)}
        />
      )}

      {channels.slack.configured && !!slackChannel && (
        <SlackChannelEdit
          channel={slackChannel}
          channelSpec={channels.slack}
          onChange={newConfig => onChannelChange(slackChannel, newConfig)}
          onRemoveChannel={() => onRemoveChannel(slackChannel)}
        />
      )}

      {hookChannels.map(channel => (
        <WebhookChannelEdit
          key={`webhook-${channel.channel_id}`}
          notificationChannel={
            notificationChannels.find(({ id }) => id === channel.channel_id)!
          }
          onRemoveChannel={() => onRemoveChannel(channel)}
        />
      ))}

      {!!channelsToAdd.length && (
        <NotificationChannelsAddMenu
          channelsToAdd={channelsToAdd}
          onAddChannel={addChannel}
        />
      )}
    </Stack>
  );
};
