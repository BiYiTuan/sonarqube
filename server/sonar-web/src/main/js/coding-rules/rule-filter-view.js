/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
define([
  'issue/views/action-options-view',
  'templates/coding-rules'
], function (ActionOptionsView) {
  var $ = jQuery;

  return ActionOptionsView.extend({
    template: Templates['coding-rules-rule-filter-form'],

    selectOption: function (e) {
      var property = $(e.currentTarget).data('property'),
          value = $(e.currentTarget).data('value');
      this.trigger('select', property, value);
      return ActionOptionsView.prototype.selectOption.apply(this, arguments);
    },

    serializeData: function () {
      return _.extend(ActionOptionsView.prototype.serializeData.apply(this, arguments), {
        tags: _.union(this.model.get('sysTags'), this.model.get('tags'))
      });
    }

  });
});
