/**
 * Copyright © 2022 650 Industries.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
import fs from 'fs';
import { ConfigT } from 'metro-config';
import { ResolutionContext } from 'metro-resolver';
import path from 'path';
import resolveFrom from 'resolve-from';

import { env } from '../../../utils/env';
import { WebSupportProjectPrerequisite } from '../../doctor/web/WebSupportProjectPrerequisite';
import { PlatformBundlers } from '../platformBundlers';
import { importMetroResolverFromProject } from './resolveFromProject';
import { getAppRouterRelativeEntryPath } from './router';

function withWebPolyfills(config: ConfigT): ConfigT {
  const originalGetPolyfills = config.serializer.getPolyfills
    ? config.serializer.getPolyfills.bind(config.serializer)
    : () => [];

  const getPolyfills = (ctx: { platform: string | null | undefined }): readonly string[] => {
    if (ctx.platform === 'web') {
      return [
        // TODO: runtime polyfills, i.e. Fast Refresh, error overlay, React Dev Tools...
      ];
    }
    // Generally uses `rn-get-polyfills`
    return originalGetPolyfills(ctx);
  };

  return {
    ...config,
    serializer: {
      ...config.serializer,
      getPolyfills,
    },
  };
}

function getDefaultResolveRequest(projectRoot: string) {
  const { resolve } = importMetroResolverFromProject(projectRoot);
  return (context: ResolutionContext, moduleName: string, platform: string | null) => {
    return resolve(context, moduleName, platform);
  };
}

export type ExpoCustomMetroResolver = (
  ...args: Parameters<import('metro-resolver').CustomResolver>
) => import('metro-resolver').Resolution | null;

/** Extend the `resolver.resolveRequest` method with custom methods that can exit early by returning a `Resolution`. */
function withCustomResolvers(
  config: ConfigT,
  projectRoot: string,
  resolvers: ExpoCustomMetroResolver[]
): ConfigT {
  const originalResolveRequest =
    config.resolver.resolveRequest || getDefaultResolveRequest(projectRoot);

  return {
    ...config,
    resolver: {
      ...config.resolver,
      resolveRequest(...args: Parameters<import('metro-resolver').CustomResolver>) {
        for (const resolver of resolvers) {
          const resolution = resolver(...args);
          if (resolution) {
            return resolution;
          }
        }
        return originalResolveRequest(...args);
      },
    },
  };
}

/**
 * Apply custom resolvers to do the following:
 * - Disable `.native.js` extensions on web.
 * - Alias `react-native` to `react-native-web` on web.
 * - Redirect `react-native-web/dist/modules/AssetRegistry/index.js` to `@react-native/assets/registry.js` on web.
 */
export function withWebResolvers(config: ConfigT, projectRoot: string) {
  // Get the `transformer.assetRegistryPath`
  // this needs to be unified since you can't dynamically
  // swap out the transformer based on platform.
  const assetRegistryPath = fs.realpathSync(
    // This is the native asset registry alias for native.
    path.resolve(resolveFrom(projectRoot, 'react-native/Libraries/Image/AssetRegistry'))
    // NOTE(EvanBacon): This is the newer import but it doesn't work in the expo/expo monorepo.
    // path.resolve(resolveFrom(projectRoot, '@react-native/assets/registry.js'))
  );

  // Create a resolver which dynamically disables support for
  // `*.native.*` extensions on web.

  const { resolve } = importMetroResolverFromProject(projectRoot);

  const extraNodeModules: { [key: string]: Record<string, string> } = {
    web: {
      'react-native': path.resolve(require.resolve('react-native-web/package.json'), '..'),
    },
  };

  const preferredMainFields: { [key: string]: string[] } = {
    // Defaults from Expo Webpack. Most packages using `react-native` don't support web
    // in the `react-native` field, so we should prefer the `browser` field.
    // https://github.com/expo/router/issues/37
    web: ['browser', 'module', 'main'],
  };

  return withCustomResolvers(config, projectRoot, [
    // Add a resolver to alias the web asset resolver.
    (immutableContext: ResolutionContext, moduleName: string, platform: string | null) => {
      const context = { ...immutableContext } as ResolutionContext & { mainFields: string[] };

      // Conditionally remap `react-native` to `react-native-web`
      if (platform && platform in extraNodeModules) {
        context.extraNodeModules = extraNodeModules[platform];
      }

      const mainFields = env.EXPO_METRO_NO_MAIN_FIELD_OVERRIDE
        ? context.mainFields
        : platform && platform in preferredMainFields
        ? preferredMainFields[platform]
        : context.mainFields;

      const result = resolve(
        {
          ...context,
          preferNativePlatform: platform !== 'web',
          resolveRequest: undefined,

          // Passing `mainFields` directly won't be considered
          // we need to extend the `getPackageMainPath` directly to
          // use platform specific `mainFields`.
          getPackageMainPath(packageJsonPath) {
            // @ts-expect-error: mainFields is not on type
            const package_ = context.moduleCache.getPackage(packageJsonPath);
            return package_.getMain(mainFields);
          },
        },
        moduleName,
        platform
      );

      // Replace the web resolver with the original one.
      // This is basically an alias for web-only.
      if (
        platform === 'web' &&
        result?.type === 'sourceFile' &&
        typeof result?.filePath === 'string' &&
        result.filePath.endsWith('react-native-web/dist/modules/AssetRegistry/index.js')
      ) {
        // @ts-expect-error: `readonly` for some reason.
        result.filePath = assetRegistryPath;
      }

      return result;
    },
  ]);
}

/** Add support for `react-native-web` and the Web platform. */
export async function withMetroMultiPlatformAsync(
  projectRoot: string,
  config: ConfigT,
  platformBundlers: PlatformBundlers
) {
  // Auto pick App entry: this is injected with Babel.
  process.env.EXPO_ROUTER_APP_ROOT = getAppRouterRelativeEntryPath(projectRoot);
  process.env.EXPO_PROJECT_ROOT = process.env.EXPO_PROJECT_ROOT ?? projectRoot;

  if (platformBundlers.web === 'metro') {
    await new WebSupportProjectPrerequisite(projectRoot).assertAsync();
  } else {
    // Bail out early for performance enhancements if web is not enabled.
    return config;
  }

  return withMetroMultiPlatform(projectRoot, config, platformBundlers);
}

function withMetroMultiPlatform(
  projectRoot: string,
  config: ConfigT,
  platformBundlers: PlatformBundlers
) {
  let expoConfigPlatforms = Object.entries(platformBundlers)
    .filter(([, bundler]) => bundler === 'metro')
    .map(([platform]) => platform);

  if (Array.isArray(config.resolver.platforms)) {
    expoConfigPlatforms = [...new Set(expoConfigPlatforms.concat(config.resolver.platforms))];
  }

  // @ts-expect-error: typed as `readonly`.
  config.resolver.platforms = expoConfigPlatforms;

  config = withWebPolyfills(config);

  return withWebResolvers(config, projectRoot);
}
