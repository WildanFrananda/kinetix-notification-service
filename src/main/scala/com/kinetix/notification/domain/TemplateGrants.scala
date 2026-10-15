package com.kinetix.notification.domain

final case class TemplateGrants(granted: Map[Caller, Set[Template]]):
  def permits(caller: Caller, template: Template): Boolean =
    granted.get(caller).exists(_.contains(template))

object TemplateGrants:
  val none: TemplateGrants = TemplateGrants(Map.empty)
